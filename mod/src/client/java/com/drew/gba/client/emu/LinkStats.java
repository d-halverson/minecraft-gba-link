package com.drew.gba.client.emu;

/**
 * Link performance counters for one session. Free of Minecraft classes. All {@code record*} / {@code roll} calls
 * happen on the emulator thread; {@link Snapshot}s are immutable and may be read from any thread.
 *
 * <p>An <em>exchange</em> is one serial transfer: the master's START message to its DONE message (the master is
 * frozen in LINK_WAIT in between, so that span is exactly the stall). Children count exchanges when the master's
 * DONE arrives. The minimum per-exchange wait of a window is a cheap partner round-trip estimate (network round
 * trip plus the partner's reaction time); no extra network messages are needed.
 */
public final class LinkStats {
	/** Link message types (native/README.md). */
	public static final int MSG_START = 1, MSG_REPLY = 2, MSG_DONE = 3;

	public record Snapshot(double seconds, double exchangesPerSec, double waitPercent, double avgWaitMs,
			double maxWaitMs, double minWaitMs, double fps, int exchanges, boolean master) {
		public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, false);

		/** One line for the GBA screen / CRT hint. Children cannot see wait times, so they get a shorter line. */
		public String format() {
			if (seconds <= 0) return "";
			if (!master) return String.format("Link: %.0f xchg/s | %.1f fps", exchangesPerSec, fps);
			if (exchanges == 0) return String.format("Link: 0 xchg/s | wait %.0f%% | %.1f fps", waitPercent, fps);
			return String.format("Link: %.0f xchg/s | wait %.0f%% avg %.1f max %.0f ms | %.1f fps | rtt~%.0f ms",
				exchangesPerSec, waitPercent, avgWaitMs, maxWaitMs, fps, minWaitMs);
		}
	}

	/** One accumulation window; two of them let the on-screen readout (1 s) and the log (5 s) roll independently. */
	private static final class Acc {
		long startNanos, frames, exchanges, waitedNanos, sumWait, maxWait, minWait = Long.MAX_VALUE;
		boolean master;

		void reset(long now) {
			startNanos = now;
			frames = exchanges = waitedNanos = sumWait = maxWait = 0;
			minWait = Long.MAX_VALUE;
			master = false;
		}

		Snapshot snap(long now) {
			double s = Math.max(1e-9, (now - startNanos) / 1e9);
			double avg = exchanges == 0 ? 0 : sumWait / (double) exchanges / 1e6;
			return new Snapshot(s, exchanges / s, 100.0 * waitedNanos / Math.max(1, now - startNanos), master ? avg : 0,
				maxWait / 1e6, minWait == Long.MAX_VALUE ? 0 : minWait / 1e6, frames / s, (int) exchanges, master);
		}
	}

	private final Acc display = new Acc(), log = new Acc();
	private long startedAt; // START timestamp of the master's transfer in flight
	private boolean inFlight;
	private volatile Snapshot latest = Snapshot.EMPTY;

	public LinkStats(long nowNanos) {
		display.reset(nowNanos);
		log.reset(nowNanos);
	}

	public Snapshot latest() { return latest; }

	/** A frame finished. */
	public void recordFrame() {
		display.frames++;
		log.frames++;
	}

	/** Time spent blocked in LINK_WAIT (the emulator made no progress). */
	public void recordBlocked(long nanos) {
		display.waitedNanos += nanos;
		log.waitedNanos += nanos;
	}

	/** A message this core emitted (to be sent to the partners). */
	public void onOutgoing(byte[] m, long now) {
		if (m.length == 0) return;
		int t = m[0] & 0xFF;
		if (t == MSG_START) {
			startedAt = now;
			inFlight = true;
		} else if (t == MSG_DONE) {
			long w = inFlight ? now - startedAt : 0;
			inFlight = false;
			addExchange(true, w);
		}
	}

	/** A message delivered from another player (a child counts exchanges when the master's DONE arrives). */
	public void onIncoming(byte[] m) {
		if (m.length > 0 && (m[0] & 0xFF) == MSG_DONE) addExchange(false, 0);
	}

	private void addExchange(boolean asMaster, long wait) {
		for (Acc a : new Acc[] {display, log}) {
			a.exchanges++;
			if (asMaster) {
				a.master = true;
				a.sumWait += wait;
				a.maxWait = Math.max(a.maxWait, wait);
				a.minWait = Math.min(a.minWait, wait);
			}
		}
	}

	/** Roll the 1 s display window if due; call once per frame. */
	public void tick(long now) {
		if (now - display.startNanos >= 1_000_000_000L) {
			latest = display.snap(now);
			display.reset(now);
		}
	}

	/** Returns the log window if at least {@code periodNanos} elapsed (and starts a new one), else null. */
	public Snapshot rollLog(long now, long periodNanos) {
		if (now - log.startNanos < periodNanos) return null;
		Snapshot s = log.snap(now);
		log.reset(now);
		return s;
	}

	/** Forget the window (cable unplugged / re-plugged) so stale numbers do not linger. */
	public void clear(long now) {
		display.reset(now);
		log.reset(now);
		inFlight = false;
		latest = Snapshot.EMPTY;
	}
}
