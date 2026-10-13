package com.drew.gba.client.emu;

import com.drew.gba.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs one game on a dedicated thread at 59.7275 fps. All native calls happen on that thread.
 * Everything the UI thread does goes through volatile fields or the command/link queues.
 */
public final class EmulatorSession implements Runnable {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink");
	private static final double FPS = 59.7275;
	private static final long FRAME_NANOS = (long) (1_000_000_000L / FPS);
	private static final long LINK_TIMEOUT_NANOS = 5_000_000_000L;
	private static final long SAVE_INTERVAL_NANOS = 10_000_000_000L;
	private static final int CHUNK = Payloads.MAX_CHUNK;

	public final UUID pakId;
	public final String title;
	public final Path romPath;
	public final Path stateFile;
	private final GbaNative natives;
	private final byte[] initialSave;
	public final FrameBuffer frames = new FrameBuffer();

	private final Thread thread;
	private final AudioOut audio;
	private volatile boolean stop;
	private volatile boolean finished;
	private volatile String error;

	/** Keys held (GBA KEYINPUT bit order) plus a latch so very short taps are never missed. */
	private volatile int held;
	private final AtomicInteger latched = new AtomicInteger();
	public volatile boolean turbo;

	private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
	private final LinkPump link;
	private volatile String toast = "";
	private volatile long toastUntil;

	public EmulatorSession(GbaNative natives, UUID pakId, String title, Path romPath, Path stateFile, byte[] save,
			int initLinkIndex, int initLinkCount, List<String> initLinkNames) {
		this.natives = natives;
		this.pakId = pakId;
		this.title = title;
		this.romPath = romPath;
		this.stateFile = stateFile;
		this.initialSave = save;
		this.link = new LinkPump(m -> sendQuiet(new Payloads.LinkDataC2S(m)), System::nanoTime, LINK_TIMEOUT_NANOS,
			() -> toast("Link partner not responding - cable unplugged"), initLinkIndex, initLinkCount, initLinkNames);
		this.audio = new AudioOut();
		this.thread = new Thread(this, "gbalink-emulator");
		this.thread.setDaemon(true);
	}

	public void start() {
		thread.start();
	}

	// ------------------------------------------------------------------ UI-thread API

	public void press(int bit) { held |= 1 << bit; latched.accumulateAndGet(1 << bit, (a, b) -> a | b); }
	public void release(int bit) { held &= ~(1 << bit); }
	public void releaseAll() { held = 0; }
	public int heldMask() { return held; }
	public void setVolume(float v) { audio.volume = v; }

	public boolean isFinished() { return finished; }
	public String error() { return error; }
	public boolean audioAvailable() { return audio.available(); }

	public void saveStateAsync() { commands.add(this::doSaveState); }
	public void loadStateAsync() { commands.add(this::doLoadState); }
	public void resetAsync() { commands.add(() -> { core.reset(); toast("Reset"); }); }

	/** Dev/test hook: run something against the core on the emulator thread. */
	public <T> java.util.concurrent.CompletableFuture<T> debugRun(java.util.function.Function<GbaCore, T> f) {
		java.util.concurrent.CompletableFuture<T> fut = new java.util.concurrent.CompletableFuture<>();
		commands.add(() -> {
			try { fut.complete(f.apply(core)); } catch (Throwable t) { fut.completeExceptionally(t); }
		});
		return fut;
	}

	/** Dev/test hook: write one EWRAM word via a save-state round trip, then re-apply the cable. */
	public java.util.concurrent.CompletableFuture<Boolean> debugPatchEwram(int off, int value) {
		return debugRun(c -> {
			byte[] st = c.saveState();
			java.nio.ByteBuffer.wrap(st).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x21000 + off, value);
			boolean ok = c.loadState(st);
			link.apply(link.index(), link.count(), link.names());
			return ok;
		});
	}

	/** Dev/test hook: a copy of the current save state (EWRAM lives at 0x21000). */
	public java.util.concurrent.CompletableFuture<byte[]> debugState() {
		return debugRun(GbaCore::saveState);
	}

	public int linkCount() { return link.count(); }
	public int linkIndex() { return link.index(); }
	public boolean linkTimedOut() { return link.timedOut(); }

	public void onLinkState(int index, int count, List<String> names) {
		commands.add(() -> link.apply(index, count, names));
	}

	public void onLinkData(int from, byte[] bytes) {
		link.offer(from, bytes);
	}

	public String toast() {
		return System.currentTimeMillis() < toastUntil ? toast : "";
	}

	public String linkStatus() {
		int c = link.count(), i = link.index();
		if (link.timedOut()) return "Link: partner not responding - cable unplugged";
		if (c < 2) return "Link: not connected";
		String names = String.join(", ", link.names());
		return (link.waiting() ? "Link: waiting for partner... " : "Link: ") + "P" + (i + 1) + " of " + c + " - " + names;
	}

	/** Ask the thread to finish (flushes the save, tells the server) and wait briefly for it. */
	public void stopAndWait(long millis) {
		stop = true;
		try {
			thread.join(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Stop without talking to the server (disconnect). */
	public void abort() {
		suppressNet = true;
		stop = true;
	}

	private volatile boolean suppressNet;

	// ------------------------------------------------------------------ emulator thread

	private GbaCore core;

	private void toast(String s) {
		toast = s;
		toastUntil = System.currentTimeMillis() + 3000;
	}

	@Override
	public void run() {
		try {
			audio.open();
			core = new GbaCore(natives);
			if (core.loadRom(romPath) < 0) throw new IllegalStateException("Could not load " + romPath.getFileName());
			if (initialSave != null && initialSave.length > 0) {
				if (core.loadSave(initialSave) < 0) LOG.warn("Core rejected save for pak {}", pakId);
				else LOG.info("Loaded {} byte save for pak {}", initialSave.length, pakId);
			}
			link.bind(core);
			link.apply(link.index(), link.count(), link.names());
			loop();
		} catch (Throwable t) {
			LOG.error("Emulator thread failed", t);
			error = "Emulator error: " + t;
		} finally {
			try {
				if (core != null) flushSave(true);
			} catch (Throwable t) {
				LOG.warn("Final save failed", t);
			}
			if (!suppressNet) sendQuiet(new Payloads.Close(pakId));
			if (core != null) core.close();
			audio.close();
			finished = true;
		}
	}

	private void loop() {
		short[] pcm = new short[2048 * 2];
		long next = System.nanoTime();
		long lastSave = next;
		while (!stop) {
			drainCommands();
			core.setKeys(held | latched.getAndSet(0));
			link.deliverIncoming();
			if (!runOneFrame()) break;
			frames.publish(core::copyFramebuffer);
			int got;
			while ((got = core.readAudio(pcm, 2048)) > 0) {
				if (!turbo) audio.write(pcm, got);
				if (got < 2048) break;
			}
			long now = System.nanoTime();
			if (now - lastSave > SAVE_INTERVAL_NANOS) {
				lastSave = now;
				flushSave(false);
			}
			if (turbo) { next = now; continue; }
			next += FRAME_NANOS;
			// Frame pacing. While cabled, block on the link queue instead of sleeping, so a partner's message is
			// delivered (and the core's answer sent) immediately instead of at the next frame boundary.
			while (!stop) {
				long wait = next - System.nanoTime();
				if (wait <= 0) break;
				if (link.count() >= 2) {
					if (link.awaitAndDeliver(wait > 1_000_000 ? wait - 500_000 : 0)) {
						// The core answers (a child's reply) only after emulating a little further, so run the next
						// frame now instead of at its slot. Never more than one frame ahead of schedule, so the
						// long-run rate stays 59.73 fps.
						if (next - System.nanoTime() <= FRAME_NANOS) break;
					} else {
						if (System.nanoTime() >= next) break;
						if (wait <= 1_000_000) Thread.onSpinWait();
					}
				} else if (wait > 1_500_000) {
					LockSupport.parkNanos(wait - 1_000_000);
				} else {
					Thread.onSpinWait();
				}
			}
			long lag = System.nanoTime() - next;
			if (lag > 100_000_000L) next = System.nanoTime();
		}
	}

	/** Runs the core to the end of one frame, servicing the link cable. Returns false on a fatal error. */
	private boolean runOneFrame() {
		int r = link.runFrame(() -> stop, this::drainCommands);
		if (r == GbaNative.RUN_ERROR) {
			error = "The emulator core reported an error";
			return false;
		}
		return true;
	}

	private void drainCommands() {
		Runnable r;
		while ((r = commands.poll()) != null) {
			try {
				r.run();
			} catch (Throwable t) {
				LOG.warn("Emulator command failed", t);
			}
		}
	}

	private void sendQuiet(net.minecraft.network.protocol.common.custom.CustomPacketPayload p) {
		try {
			ClientPlayNetworking.send(p);
		} catch (Throwable ignored) {
			// not connected any more
		}
	}

	private void flushSave(boolean closing) {
		if (!core.saveDirty()) return;
		byte[] save = core.getSave();
		if (save == null || save.length == 0 || save.length > Payloads.MAX_SAVE || suppressNet) return;
		int off = 0;
		do {
			int len = Math.min(CHUNK, save.length - off);
			sendQuiet(new Payloads.SaveChunk(pakId, off, save.length, Arrays.copyOfRange(save, off, off + len)));
			off += len;
		} while (off < save.length);
		if (!closing) toast("Game saved");
	}

	private void doSaveState() {
		byte[] st = core.saveState();
		if (st == null) { toast("Save state failed"); return; }
		try {
			Files.createDirectories(stateFile.getParent());
			Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
			Files.write(tmp, st);
			Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			toast("State saved");
		} catch (IOException e) {
			toast("Save state failed: " + e.getMessage());
		}
	}

	private void doLoadState() {
		try {
			if (!Files.isRegularFile(stateFile)) { toast("No saved state"); return; }
			toast(core.loadState(Files.readAllBytes(stateFile)) ? "State loaded" : "State load failed");
		} catch (IOException e) {
			toast("Load state failed: " + e.getMessage());
		}
	}
}
