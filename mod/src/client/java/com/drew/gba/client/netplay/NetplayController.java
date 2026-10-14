package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Session-level netplay state machine (no Minecraft classes): owns the console that is shown (a normal standalone core, or
 * the local console inside the netplay simulation), starts and ends netplay sessions at the server's request and runs the
 * paced frame loop while netplay is active. Used by EmulatorSession and by the headless tests.
 */
public final class NetplayController {
	public static final double FPS = 59.7275;
	public static final long FRAME_NANOS = (long) (1_000_000_000L / FPS);

	public interface Events {
		void toast(String message);
		void log(String message);
		/** The session left netplay without being asked to (failure, timeout). */
		default void left(String reason) {}
		default void desync(int frame) {}
		default void resynced(int frame) {}
	}

	/** Receives every simulated frame of the local console (picture and sound). */
	public interface FrameSink {
		void frame(GbaCore local);
	}

	private final GbaNative natives;
	private final Path rom;
	private final NetplayEngine.Transport tx;
	private final LongSupplier clock;
	private final Events ev;

	private GbaCore core;          // the console the session shows
	private GbaCore standalone;    // the original core while it is still the live one (closed when netplay takes over)
	private volatile NetplayEngine engine;
	private long lastSession;      // highest session id an engine was created for
	private final List<Object[]> early = new ArrayList<>();
	private long next;
	private boolean wasRunning;

	public NetplayController(GbaNative natives, Path rom, GbaCore core, NetplayEngine.Transport tx, LongSupplier clock, Events ev) {
		this.natives = natives;
		this.rom = rom;
		this.core = core;
		this.standalone = core;
		this.tx = tx;
		this.clock = clock;
		this.ev = ev;
	}

	/** Attach the first console (the controller is created before the emulator thread creates its core). */
	public void setCore(GbaCore c) {
		this.core = c;
		this.standalone = c;
	}

	public GbaCore core() { return core; }
	public boolean active() { return engine != null; }
	public NetplayEngine engine() { return engine; }
	public boolean running() { NetplayEngine e = engine; return e != null && e.state() == NetplayEngine.State.RUNNING; }

	// ------------------------------------------------------------------ inbound messages (any thread)

	/** Routes a message to the engine of its session; messages for a session whose start has not been processed yet are kept. */
	private void route(long session, Consumer<NetplayEngine> apply) {
		synchronized (early) {
			NetplayEngine e = engine;
			if (e != null && e.params().sessionId() == session) {
				apply.accept(e);
			} else if (session > lastSession && early.size() < 512) {
				early.add(new Object[] {session, apply});
			}
		}
	}

	public void onInput(long session, int from, int first, short[] recs, int sendMs, int[] echo) {
		route(session, e -> e.onInput(session, from, first, recs, sendMs, echo));
	}

	public void onHash(long session, int from, int gen, int frame, int hash) {
		route(session, e -> e.onHash(session, from, gen, frame, hash));
	}

	public void onSnapshot(long session, int from, int gen, int frame, int offset, int total, byte[] bytes) {
		route(session, e -> e.onSnapshot(session, from, gen, frame, offset, total, bytes));
	}

	// ------------------------------------------------------------------ lifecycle (emulator thread)

	/** The server starts (or restarts) a netplay session: snapshot the local console and send it. */
	public void start(NetplayEngine.Params p) {
		if (engine != null) leave(false);
		if (!natives.supportsNetplay()) { ev.toast("Netplay needs a newer emulator library"); return; }
		byte[] state = core.saveState();
		byte[] save = core.getSave();
		if (state == null) { ev.toast("Could not start the link (state)"); return; }
		core.linkConfigure(0, 0);
		NetplayEngine e = new NetplayEngine(natives, rom, p, tx, clock, new NetplayEngine.Listener() {
			@Override public void log(String m) { ev.log(m); }
			@Override public void desync(int f, int g) { ev.desync(f); }
			@Override public void resynced(int f, int g) { ev.resynced(f); }
		});
		synchronized (early) {
			engine = e;
			lastSession = Math.max(lastSession, p.sessionId());
			List<Object[]> keep = new ArrayList<>();
			for (Object[] m : early) {
				long sid = (Long) m[0];
				if (sid == p.sessionId()) {
					@SuppressWarnings("unchecked") Consumer<NetplayEngine> c = (Consumer<NetplayEngine>) m[1];
					c.accept(e);
				} else if (sid > p.sessionId()) keep.add(m);
			}
			early.clear();
			early.addAll(keep);
		}
		e.begin(state, save == null ? new byte[0] : save);
		next = 0;
		wasRunning = false;
	}

	/** Leave netplay (server ended it, the cable broke, the player closed or turned something off). The local console keeps its state. */
	public void end() {
		if (engine != null) leave(true);
	}

	private void leave(boolean toast) {
		NetplayEngine e = engine;
		if (e == null) return;
		synchronized (early) { engine = null; }
		GbaCore local = e.release();
		e.close();
		if (local != null) {
			core = local;
			if (standalone != null && standalone != local) { standalone.close(); }
			standalone = local;
			core.linkConfigure(0, 0);
			core.setRtc(0, GbaNative.RTC_WALL);
		}
		ev.log("left netplay session " + e.params().sessionId());
	}

	/** Closes whatever this controller still owns besides the shown console. */
	public void shutdown() {
		NetplayEngine e = engine;
		if (e != null) leave(false);
	}

	/**
	 * One iteration of the emulator loop while netplay is active (starting or running): paces at 59.7275 fps, advances the
	 * simulation when all inputs are known, catches up a few frames after a stall. Returns false if netplay is not active.
	 */
	public boolean iterate(IntSupplier keys, FrameSink sink, BooleanSupplier stop) {
		NetplayEngine e = engine;
		if (e == null) return false;
		e.pump(0);
		switch (e.state()) {
			case CONNECTING -> {
				e.pump(2_000_000L);
				return true;
			}
			case FAILED -> {
				String why = e.failure();
				leave(true);
				ev.toast(why);
				ev.left(why);
				return true;
			}
			default -> { }
		}
		if (!wasRunning) { // the simulation just started: it owns the consoles now
			wasRunning = true;
			next = clock.getAsLong();
			if (standalone != null && standalone != e.localCore()) { standalone.close(); standalone = null; }
		}
		core = e.localCore();
		long now = clock.getAsLong();
		if (!e.catchingUp() && now < next) {
			long wait = next - now;
			if (wait > 2_000_000L) e.pump(wait - 1_000_000L);
			else Thread.onSpinWait();
			return true;
		}
		NetplayEngine.Step s = e.advance(keys);
		if (s == NetplayEngine.Step.STALLED) {
			e.pump(1_000_000L);
			return true;
		}
		if (s == NetplayEngine.Step.RAN) {
			core = e.localCore();
			sink.frame(core);
			next += FRAME_NANOS;
			if (clock.getAsLong() - next > 100_000_000L) next = clock.getAsLong(); // too far behind: skip ahead instead of racing
		}
		return true;
	}

	public String statusLine() {
		NetplayEngine e = engine;
		return e == null ? "" : e.statusLine();
	}

	public String statsLine() {
		NetplayEngine e = engine;
		return e == null ? "" : e.statsLine();
	}
}
