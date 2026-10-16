package com.drew.gba.client.emu;

import com.drew.gba.client.netplay.NetplayController;
import com.drew.gba.client.netplay.NetplayEngine;
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
			int linkIndex, int linkCount, List<String> linkNames, String system) {
		/** A GBA game (what every session was before 0.5.0). */
		public Config(UUID pakId, String title, String romSha1, Path romPath, Path stateFile, Path suspendFile, Path mirrorFile,
				Path exportDir, byte[] save, String serverRevision, byte[] resumeState, String startToast, int linkIndex, int linkCount,
				List<String> linkNames) {
			this(pakId, title, romSha1, romPath, stateFile, suspendFile, mirrorFile, exportDir, save, serverRevision, resumeState,
				startToast, linkIndex, linkCount, linkNames, "gba");
		}
	}

	/** Shown while cabled with a Game Boy / Game Boy Color game open (phase 1: no link cable for those games yet). */
	public static final String GB_LINK_UNSUPPORTED = "Link cable for Game Boy games isn't supported yet";

	public final UUID pakId;
	public final String title;
	public final Path romPath;
	public final Path stateFile;
	private final Config cfg;
	private final GbaNative natives;
	private final byte[] initialSave;
	/** "gba", "gb" or "gbc" (from the ROM's header, see RomHeader). */
	private final String system;
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
	/** Input-sync netplay (same ROM in every linked pak). Created with the session, driven by the emulator thread. */
	private final NetplayController netplay;
	/** The server's last link_state, so the classic cable can be restored when netplay ends. */
	private volatile int lastLinkMode;
	/** Shown once each time a link falls back to the classic (lockstep) mode. */
	static final String CLASSIC_WARNING = "Different games: slow link, may fail over the internet. Use the same ROM for full speed.";
	private volatile boolean classicWarned;
	private volatile boolean netplayExpected, netplayActive;
	private long lastSaveNanos;
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
		this.system = c.system() == null ? "gba" : c.system();
		this.currentRev = c.serverRevision();
		this.link = new LinkPump(m -> sendQuiet(new Payloads.LinkDataC2S(m)), System::nanoTime, LINK_TIMEOUT_NANOS,
			() -> toast("Link partner not responding - cable unplugged"), c.linkIndex(), c.linkCount(), c.linkNames());
		this.netplay = new NetplayController(natives, c.romPath(), null, new NetTransport(), System::nanoTime, new NetEvents());
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

	/** {@code gba}, {@code gb} or {@code gbc}. */
	public String system() { return system; }

	/** True for Game Boy and Game Boy Color games. */
	public boolean isGameBoy() { return !"gba".equals(system); }

	// ------------------------------------------------------------------ UI-thread API

	public void press(int bit) { held |= 1 << bit; latched.accumulateAndGet(1 << bit, (a, b) -> a | b); }
	public void release(int bit) { held &= ~(1 << bit); }
	public void releaseAll() { held = 0; }
	public int heldMask() { return held; }
	/** True (with the netplay toast) if an import must not start now; the screens check this before opening the picker. */
	public boolean importBlocked() { return netplayBlocked("Import"); }

	public boolean isFinished() { return finished; }
	public String error() { return error; }
	public boolean audioAvailable() { return audio.available(); }

	public void saveStateAsync() { if (netplayBlocked("Save state")) return; commands.add(this::doSaveState); }
	public void loadStateAsync() { if (netplayBlocked("Load state")) return; commands.add(this::doLoadState); }
	public void exportSaveAsync() { commands.add(this::doExport); }
	/** Replace this cartridge's save with {@code data} (read from {@code fileName}) and cold-boot from it. */
	public void importSaveAsync(String fileName, byte[] data) {
		if (netplayBlocked("Import")) return;
		commands.add(() -> doImport(fileName, data));
	}

	/** True (with a toast) if netplay is running: some actions would desynchronise the linked consoles. */
	private boolean netplayBlocked(String what) {
		if (!netplayActive && !netplayExpected) return false;
		toast(what + " is off while linked in netplay - unlink or close the other GBA first", 4000);
		return true;
	}

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

	/** Dev/test hook: the current battery save, read like the export does (does not lose the "needs upload" state). */
	public java.util.concurrent.CompletableFuture<byte[]> debugSave() {
		return debugRun(c -> readSave());
	}

	/** Dev/test hook: a copy of the current save state (EWRAM lives at 0x21000). */
	public java.util.concurrent.CompletableFuture<byte[]> debugState() {
		return debugRun(GbaCore::saveState);
	}

	public int linkCount() { return link.count(); }
	public int linkIndex() { return link.index(); }
	public boolean linkTimedOut() { return link.timedOut(); }

	/** True while leaving would break a link: netplay running or starting, or a classic link with 2+ consoles that still answers. */
	public boolean linkActive() {
		return com.drew.gba.client.ui.LeaveGuard.needsConfirm(finished, netplayActive, netplayExpected, link.count(), link.timedOut());
	}

	/** The other players on this link (cable state first, the classic link's names as a fallback). */
	public List<String> linkPartners() {
		List<String> p = com.drew.gba.client.ClientCable.partners();
		if (!p.isEmpty()) return p;
		List<String> out = new java.util.ArrayList<>();
		List<String> all = link.names();
		for (int i = 0; i < all.size(); i++) if (i != link.index()) out.add(all.get(i));
		return out;
	}

	public void onLinkState(int index, int count, List<String> names) {
		onLinkState(index, count, names, Payloads.LinkState.MODE_CLASSIC);
	}

	/** link_state from the server. mode NETPLAY: the cable is handled by NetplayController once netplay_start arrives. */
	public void onLinkState(int index, int count, List<String> names, int mode) {
		if (isGameBoy()) { // no link cable for Game Boy games yet: always unplugged, whatever the server says
			index = 0;
			count = 0;
			names = List.of();
			mode = Payloads.LinkState.MODE_CLASSIC;
		}
		final int fIndex = index, fCount = count;
		final List<String> fNames = names;
		final int fMode = mode;
		boolean classicNow = mode != Payloads.LinkState.MODE_NETPLAY && count >= 2;
		if (classicNow && !classicWarned) toast(CLASSIC_WARNING, 8000);
		classicWarned = classicNow;
		lastLinkMode = mode;
		netplayExpected = mode == Payloads.LinkState.MODE_NETPLAY && count >= 2;
		commands.add(() -> {
			if (fMode == Payloads.LinkState.MODE_NETPLAY && fCount >= 2) {
				link.apply(0, 0, List.of()); // the classic cable stays unplugged; netplay_start follows
				return;
			}
			leaveNetplay();
			link.apply(fIndex, fCount, fNames);
		});
	}

	/** netplay_start from the server: a (new) netplay session for this client. */
	public void onNetplayStart(NetplayEngine.Params p) {
		if (isGameBoy()) return; // never expected (the server leaves Game Boy games out of the link)
		commands.add(() -> {
			if (core == null) return;
			link.apply(0, 0, List.of());
			netplay.start(p);
			netplayActive = netplay.active();
			syncNetplayCore();
		});
	}

	public NetplayController netplay() { return netplay; }

	/** Emulator thread: take over the console the netplay controller owns (the local sim core, or the original one again). */
	private void syncNetplayCore() {
		GbaCore c = netplay.core();
		if (c != null && c != core) {
			core = c;
			link.bind(core);
		}
		netplayActive = netplay.active();
	}

	private void leaveNetplay() {
		if (!netplay.active()) return;
		netplay.end();
		syncNetplayCore();
		netplayExpected = false;
		linkStats().clear(System.nanoTime());
		toast("Link ended", 2500);
	}

	private LinkStats linkStats() { return link.stats(); }

	public void onLinkData(int from, byte[] bytes) {
		link.offer(from, bytes);
	}

	public String toast() {
		return System.currentTimeMillis() < toastUntil ? toast : "";
	}

	/** One-line link performance readout ("" when not cabled or nothing measured yet). */
	public String linkStatsLine() {
		if (netplayActive) return netplay.statsLine();
		if (link.count() < 2 || link.timedOut()) return "";
		return link.stats().latest().format();
	}

	private static final long LINK_LOG_PERIOD_NANOS = 5_000_000_000L;
	private LinkLog linkLog;

	/** Emulator thread, once per frame: roll the stats windows and append the 5 s line to gbalink/link.log while cabled. */
	private void statsTick() {
		long now = System.nanoTime();
		LinkStats st = link.stats();
		st.tick(now);
		if (link.count() < 2) {
			st.rollLog(now, LINK_LOG_PERIOD_NANOS); // keep the window fresh so the first line after plugging is a full window
			return;
		}
		LinkStats.Snapshot s = st.rollLog(now, LINK_LOG_PERIOD_NANOS);
		if (s == null) return;
		try {
			if (linkLog == null) linkLog = new LinkLog(cfg.exportDir().getParent().resolve("link.log"), LinkLog.DEFAULT_MAX_BYTES);
			linkLog.append(LinkLog.line(System.currentTimeMillis(), title, link.index() == 0 ? "master" : "child", link.count(),
				s.format().replaceFirst("^Link: ", "")));
		} catch (RuntimeException e) {
			// diagnostics must never disturb emulation
		}
	}

	/**
	 * One short phrase for the cable HUD while this session is open: {@code netplay · 60 fps · delay 3f},
	 * {@code classic link (different games)}, {@code connecting…}, or "" when the cable is not (yet) effectively linked.
	 */
	public String linkHudState() {
		if (isGameBoy()) return com.drew.gba.client.ClientCable.linked() ? GB_LINK_UNSUPPORTED : "";
		if (netplayActive) {
			String st = netplay.statsLine();
			java.util.regex.Matcher fps = java.util.regex.Pattern.compile("([0-9.]+) fps").matcher(st);
			java.util.regex.Matcher delay = java.util.regex.Pattern.compile("delay (\\d+)f").matcher(st);
			if (!fps.find()) return "connecting\u2026";
			return "netplay \u00b7 " + Math.round(Double.parseDouble(fps.group(1))) + " fps" + (delay.find() ? " \u00b7 delay " + delay.group(1) + "f" : "");
		}
		if (netplayExpected) return "connecting\u2026";
		if (link.timedOut()) return "partner not responding";
		if (link.count() < 2) return "";
		return lastLinkMode == Payloads.LinkState.MODE_NETPLAY ? "linked" : "classic link (different games)";
	}

	public String linkStatus() {
		if (isGameBoy()) return com.drew.gba.client.ClientCable.linked() ? GB_LINK_UNSUPPORTED : "Link: not connected";
		if (netplayActive) return netplay.statusLine();
		if (netplayExpected) return "Connecting link...";
		int c = link.count(), i = link.index();
		if (link.timedOut()) return "Link: partner not responding - cable unplugged";
		if (c < 2) return "Link: not connected";
		String names = String.join(", ", link.names());
		String head = lastLinkMode == Payloads.LinkState.MODE_NETPLAY ? "Link" : "Link (different games \u2014 slow, may fail online)";
		return (link.waiting() ? head + ": waiting for partner... " : head + ": ") + "P" + (i + 1) + " of " + c + " - " + names;
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
			if (core.isGameBoy() != isGameBoy()) {
				throw new IllegalStateException(isGameBoy()
					? "The emulator library cannot run Game Boy games - update the GBA Link mod"
					: romPath.getFileName() + " is not a GBA game");
			}
			if (isGameBoy() && com.drew.gba.client.ClientCable.linked()) toast(GB_LINK_UNSUPPORTED, 6000);
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
			netplay.setCore(core);
			link.bind(core);
			link.apply(link.index(), link.count(), link.names());
			loop();
		} catch (Throwable t) {
			LOG.error("Emulator thread failed", t);
			error = "Emulator error: " + t;
		} finally {
			try {
				if (netplay.active()) { netplay.end(); syncNetplayCore(); } // the local console keeps its state and suspends normally
				netplay.shutdown();
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
		lastSaveNanos = next;
		boolean wasNetplay = false;
		while (!stop) {
			drainCommands();
			if (netplay.active()) {
				wasNetplay = true;
				if (!runNetplay(pcm)) break;
				continue;
			}
			if (wasNetplay) { // the standalone clock restarts after netplay
				wasNetplay = false;
				next = System.nanoTime();
				lastSave = lastSaveNanos;
			}
			core.setKeys(held | latched.getAndSet(0));
			link.deliverIncoming();
			if (!runOneFrame()) break;
			statsTick();
			frames.publish(core);
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
				lastSave = lastSaveNanos = now;
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

	private short[] npPcm = new short[2048 * 2];
	private long lastNpLog;

	/** One iteration while netplay is starting or running. Returns false on a fatal error. */
	private boolean runNetplay(short[] pcm) {
		if (turbo) { turbo = false; toast("Fast-forward is off while linked in netplay", 3000); }
		boolean ok = netplay.iterate(() -> (held | latched.getAndSet(0)) & 0x3FF, this::netplayFrame, () -> stop);
		syncNetplayCore();
		long now = System.nanoTime();
		if (netplayActive && netplay.running()) {
			if (now - lastSaveNanos > SAVE_INTERVAL_NANOS) {
				lastSaveNanos = now;
				flushSave(false); // only this client's own console is saved and uploaded
			}
			if (now - lastNpLog > LINK_LOG_PERIOD_NANOS) {
				lastNpLog = now;
				logLine("netplay | " + netplay.statsLine().replaceFirst("^Netplay: ", ""));
			}
		}
		if (!ok) return true;
		return error == null;
	}

	/** A netplay frame of the local console is ready: picture, sound (only this console's), CRT stream. */
	private void netplayFrame(GbaCore local) {
		frames.publish(local);
		com.drew.gba.client.crt.CrtStreamer st = streamer;
		if (st != null) st.offerFrame(frames);
		int got;
		while ((got = local.readAudio(npPcm, 2048)) > 0) {
			audio.write(npPcm, got);
			if (st != null) st.offerAudio(npPcm, got);
			if (got < 2048) break;
		}
	}

	private void logLine(String text) {
		try {
			if (linkLog == null) linkLog = new LinkLog(cfg.exportDir().getParent().resolve("link.log"), LinkLog.DEFAULT_MAX_BYTES);
			linkLog.append(LinkLog.line(System.currentTimeMillis(), title, "P" + (netplay.engine() == null ? 0 : netplay.engine().params().index() + 1),
				netplay.engine() == null ? 0 : netplay.engine().params().count(), text));
		} catch (RuntimeException e) {
			// diagnostics must never disturb emulation
		}
	}

	/** Sends netplay messages to the server (called on the emulator thread). */
	private final class NetTransport implements NetplayEngine.Transport {
		@Override public void input(long session, int first, short[] recs, int sendMs, int[] echo) {
			sendQuiet(new Payloads.NetplayInputC2S(session, first, recs, sendMs, echo));
		}

		@Override public void hash(long session, int gen, int frame, int hash) {
			sendQuiet(new Payloads.NetplayHashC2S(session, gen, frame, hash));
		}

		@Override public void snapshot(long session, int gen, int frame, byte[] blob) {
			int off = 0;
			do {
				int len = Math.min(CHUNK, blob.length - off);
				sendQuiet(new Payloads.NetplaySnapshotC2S(session, gen, frame, off, blob.length, Arrays.copyOfRange(blob, off, off + len)));
				off += len;
			} while (off < blob.length);
		}

		@Override public void restart(long session) { sendQuiet(new Payloads.NetplayRestartC2S(session)); }
	}

	private final class NetEvents implements NetplayController.Events {
		@Override public void toast(String message) { EmulatorSession.this.toast(message, 4000); }
		@Override public void log(String message) { LOG.info("[netplay] {}", message); logLine(message); }
		@Override public void left(String reason) { netplayExpected = false; }
		@Override public void desync(int frame) { EmulatorSession.this.toast("Link desync - resyncing", 3000); }
		@Override public void resynced(int frame) { EmulatorSession.this.toast("Link resynced", 2500); }
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
			SaveStore.writeAtomic(cfg.suspendFile(), new SuspendFile(rev, cfg.romSha1(), revConfirmed, st, system).encode());
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
		try {
			Path out = writeExport(save, "");
			toast("Exported gbalink/exports/" + out.getFileName(), 5000);
		} catch (IOException e) {
			toast("Export failed: " + e.getMessage());
		}
	}

	private Path writeExport(byte[] save, String suffix) throws IOException {
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		Path out = cfg.exportDir().resolve(SaveRules.safeFileName(title) + "-" + stamp + suffix + ".sav");
		SaveStore.writeAtomic(out, save);
		LOG.info("Exported save to {}", out);
		return out;
	}

	/**
	 * Import (emulator thread): export the current save as a backup, upload the new one (the server keeps its own
	 * bak1..3), and on the server's ack discard the suspend state and cold-boot the core from the imported save.
	 */
	private void doImport(String fileName, byte[] data) {
		String why = SaveRules.validateImportSize(data.length, system);
		if (why != null) { toast("Import failed: " + why, 5000); return; }
		if (suppressNet) { toast("Import failed: not connected to the server", 5000); return; }
		waitInflight(3000); // an earlier upload must not land after the import
		byte[] current = readSave();
		if (current != null && current.length > 0) {
			try {
				writeExport(current, "-before-import");
			} catch (IOException e) {
				toast("Import cancelled: could not back up the current save (" + e.getMessage() + ")", 6000);
				return;
			}
		}
		String rev = SaveRules.revision(data);
		Inflight f = new Inflight(rev, data, new java.util.concurrent.CountDownLatch(1));
		inflight = f;
		int off = 0;
		do {
			int len = Math.min(CHUNK, data.length - off);
			sendQuiet(new Payloads.ImportChunk(pakId, off, data.length, Arrays.copyOfRange(data, off, off + len)));
			off += len;
		} while (off < data.length);
		boolean got = false;
		try {
			got = f.done().await(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (!got || !rev.equals(currentRev)) {
			if (inflight == f) inflight = null;
			toast(got ? "The server refused the import" : "The server did not answer - import cancelled", 6000);
			return;
		}
		forceUpload = false;
		resumed = false;
		try {
			Files.deleteIfExists(cfg.suspendFile());
		} catch (IOException e) {
			LOG.warn("Could not delete {}", cfg.suspendFile(), e);
		}
		core.close();
		core = new GbaCore(natives);
		if (core.loadRom(romPath) < 0) throw new IllegalStateException("Could not reload " + romPath.getFileName());
		if (core.isGameBoy() != isGameBoy()) throw new IllegalStateException("The ROM changed its system while running");
		if (core.loadSave(data) < 0) LOG.warn("Core rejected the imported save for pak {}", pakId);
		link.bind(core);
		link.apply(link.index(), link.count(), link.names());
		LOG.info("Imported {} ({} bytes) into pak {}; core restarted", fileName, data.length, pakId);
		toast("Imported " + fileName, 5000);
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
