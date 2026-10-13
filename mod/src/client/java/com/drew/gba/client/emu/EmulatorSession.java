package com.drew.gba.client.emu;

import com.drew.gba.net.Payloads;
import com.drew.gba.save.SaveRules;
import com.drew.gba.save.SuspendFile;
import com.drew.gba.server.SaveStore;
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

	/** Everything a session needs to start; built by GbaLinkClient.onOpen. */
	public record Config(UUID pakId, String title, String romSha1, Path romPath, Path stateFile, Path suspendFile,
			Path mirrorFile, Path exportDir, byte[] save, String serverRevision, byte[] resumeState, String startToast,
			int linkIndex, int linkCount, List<String> linkNames) {}

	public final UUID pakId;
	public final String title;
	public final Path romPath;
	public final Path stateFile;
	private final Config cfg;
	private final GbaNative natives;
	private final byte[] initialSave;
	public final FrameBuffer frames = new FrameBuffer();

	private final Thread thread;
	private final AudioOut audio;
	private volatile com.drew.gba.client.crt.CrtStreamer streamer;
	private volatile boolean stop;
	private volatile boolean finished;
	private volatile String error;
	private volatile boolean powerOff;
	/** True if the suspend state was loaded when the session started. */
	public volatile boolean resumed;

	/** Server save revision we believe is current (set from Open, then from acks), and whether the server confirmed it. */
	private volatile String currentRev;
	private volatile boolean revConfirmed = true;
	private record Inflight(String rev, byte[] bytes, java.util.concurrent.CountDownLatch done) {}
	private volatile Inflight inflight;
	/** Emulator thread only: the save was read (clearing the core's dirty flag) but not uploaded yet. */
	private boolean forceUpload;

	/** Keys held (GBA KEYINPUT bit order) plus a latch so very short taps are never missed. */
	private volatile int held;
	private final AtomicInteger latched = new AtomicInteger();
	public volatile boolean turbo;

	private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
	private final LinkPump link;
	private volatile String toast = "";
	private volatile long toastUntil;

	public EmulatorSession(GbaNative natives, Config c) {
		this.natives = natives;
		this.cfg = c;
		this.pakId = c.pakId();
		this.title = c.title();
		this.romPath = c.romPath();
		this.stateFile = c.stateFile();
		this.initialSave = c.save();
		this.currentRev = c.serverRevision();
		this.link = new LinkPump(m -> sendQuiet(new Payloads.LinkDataC2S(m)), System::nanoTime, LINK_TIMEOUT_NANOS,
			() -> toast("Link partner not responding - cable unplugged"), c.linkIndex(), c.linkCount(), c.linkNames());
		this.audio = new AudioOut();
		this.thread = new Thread(this, "gbalink-emulator");
		this.thread.setDaemon(true);
	}

	/** CRT mode: encode and upload the picture and sound for spectators while this session runs. */
	public void attachStreamer(com.drew.gba.client.crt.CrtStreamer s) {
		this.streamer = s;
		s.start();
	}

	public com.drew.gba.client.crt.CrtStreamer streamer() { return streamer; }

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
	public void exportSaveAsync() { commands.add(this::doExport); }

	/** Real power off: the save is flushed, the suspend state is deleted, the next open cold-boots. */
	public void powerOff() { powerOff = true; stop = true; }
	public boolean isPoweredOff() { return powerOff; }
	/** Dev/test: the revision this session currently believes the server holds (and if the server confirmed it). */
	public String revision() { return currentRev; }
	public boolean revisionConfirmed() { return revConfirmed; }

	/** Called on the client thread when the server acknowledges (or refuses) a save upload. */
	public void onSaveAck(String rev, boolean accepted) {
		Inflight f = inflight;
		if (f != null && accepted && f.rev().equals(rev)) {
			try {
				SaveStore.writeAtomic(cfg.mirrorFile(), f.bytes()); // local backup copy of every successful upload
			} catch (IOException | RuntimeException e) {
				LOG.warn("Could not write the local save copy {}", cfg.mirrorFile(), e);
			}
		}
		currentRev = rev; // the server's truth, whatever happened to our upload
		revConfirmed = true;
		if (f != null && (!accepted || f.rev().equals(rev))) {
			inflight = null;
			f.done().countDown();
		}
	}

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

	/** Ask the thread to finish without waiting for it. */
	public void requestStop() { stop = true; }

	/** Stop without talking to the server (disconnect). */
	public void abort() {
		suppressNet = true;
		stop = true;
	}

	private volatile boolean suppressNet;

	// ------------------------------------------------------------------ emulator thread

	private GbaCore core;

	private void toast(String s) {
		toast(s, 3000);
	}

	private void toast(String s, long ms) {
		toast = s;
		toastUntil = System.currentTimeMillis() + ms;
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
			if (cfg.resumeState() != null) {
				if (core.loadState(cfg.resumeState())) {
					resumed = true;
					toast("Resumed");
				} else {
					core.reset();
					toast("Suspend state could not be loaded - started fresh");
				}
			} else if (cfg.startToast() != null && !cfg.startToast().isEmpty()) {
				toast(cfg.startToast(), 6000);
			}
			link.bind(core);
			link.apply(link.index(), link.count(), link.names());
			loop();
		} catch (Throwable t) {
			LOG.error("Emulator thread failed", t);
			error = "Emulator error: " + t;
		} finally {
			try {
				if (core != null) endSession();
			} catch (Throwable t) {
				LOG.warn("Final save failed", t);
			}
			if (!suppressNet) sendQuiet(new Payloads.Close(pakId));
			if (core != null) core.close();
			audio.close();
			if (streamer != null) streamer.close();
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
			com.drew.gba.client.crt.CrtStreamer st = streamer;
			if (st != null) st.offerFrame(frames);
			int got;
			while ((got = core.readAudio(pcm, 2048)) > 0) {
				if (!turbo) {
					audio.write(pcm, got);
					if (st != null) st.offerAudio(pcm, got);
				}
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

	/** Closing: flush the save, then either suspend (default) or power off (delete the suspend state). */
	private void endSession() {
		flushSave(true);
		if (powerOff || error != null) {
			try {
				Files.deleteIfExists(cfg.suspendFile());
			} catch (IOException e) {
				LOG.warn("Could not delete {}", cfg.suspendFile(), e);
			}
			return;
		}
		String rev = currentRev;
		if (rev == null || rev.isEmpty()) return; // server too old to know revisions: no suspend
		byte[] st = core.saveState();
		if (st == null) return;
		try {
			SaveStore.writeAtomic(cfg.suspendFile(), new SuspendFile(rev, cfg.romSha1(), revConfirmed, st).encode());
			LOG.info("Suspended pak {} (revision {}, confirmed={})", pakId, rev.substring(0, Math.min(8, rev.length())), revConfirmed);
		} catch (IOException e) {
			LOG.warn("Could not write the suspend state {}", cfg.suspendFile(), e);
		}
	}

	private void waitInflight(long ms) {
		Inflight f = inflight;
		if (f == null) return;
		try {
			f.done().await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Reads the battery save without losing the "needs upload" state (reading clears the core's dirty flag). */
	private byte[] readSave() {
		if (core.saveDirty()) forceUpload = true;
		return core.getSave();
	}

	private void flushSave(boolean closing) {
		if (closing && !suppressNet) waitInflight(3000); // an earlier upload's ack must land before we record the revision
		if (!forceUpload && !core.saveDirty()) return;
		byte[] save = readSave();
		if (save == null || save.length == 0 || save.length > Payloads.MAX_SAVE) { forceUpload = false; return; }
		String rev = SaveRules.revision(save);
		if (suppressNet) { // disconnected: the upload cannot happen; the suspend only stays valid if the server save matches
			currentRev = rev;
			revConfirmed = false;
			return;
		}
		forceUpload = false;
		Inflight f = new Inflight(rev, save, new java.util.concurrent.CountDownLatch(1));
		inflight = f;
		int off = 0;
		do {
			int len = Math.min(CHUNK, save.length - off);
			sendQuiet(new Payloads.SaveChunk(pakId, off, save.length, Arrays.copyOfRange(save, off, off + len)));
			off += len;
		} while (off < save.length);
		if (closing) {
			// Wait for the ack: only then is the server's revision known to include this save.
			boolean got = false;
			try {
				got = f.done().await(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!got) { // unconfirmed: record what the server would hold after the upload
				currentRev = rev;
				revConfirmed = false;
			}
		} else {
			toast("Game saved");
		}
	}

	private void doExport() {
		byte[] save = readSave();
		if (save == null || save.length == 0) { toast("This game has no save yet"); return; }
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		Path out = cfg.exportDir().resolve(SaveRules.safeFileName(title) + "-" + stamp + ".sav");
		try {
			SaveStore.writeAtomic(out, save);
			toast("Exported gbalink/exports/" + out.getFileName(), 5000);
			LOG.info("Exported save to {}", out);
		} catch (IOException e) {
			toast("Export failed: " + e.getMessage());
		}
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
