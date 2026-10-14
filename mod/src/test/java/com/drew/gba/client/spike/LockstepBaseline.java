package com.drew.gba.client.spike;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.emu.LinkPump;
import com.drew.gba.client.emu.LinkStats;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The CURRENT link design under a fake network: two cores in two threads, each driven by a LinkPump with the same pacing
 * logic as EmulatorSession.loop (59.7275 fps schedule, link-aware pacing wait), messages relayed with a one-way delay.
 */
public final class LockstepBaseline {
	private static final double FPS = 59.7275;
	public static volatile byte[] lastChildState;
	private static final long FRAME_NANOS = (long) (1_000_000_000L / FPS);

	public record Result(long oneWayMs, double masterFps, double childFps, double masterWaitPct, double avgWaitMs,
			double xchgPerSec, SpikeSupport.Counters master, SpikeSupport.Counters child, String statsLine) {}

	private static final class Player implements Runnable {
		final int idx;
		final GbaNative n;
		final Path rom;
		final Player[] all;
		final ScheduledExecutorService net;
		final long delayMs;
		volatile LinkPump pump;
		volatile boolean stop;
		volatile long frames;
		volatile byte[] finalState;
		volatile Throwable failure;
		volatile String lastStats = "";

		Player(int idx, GbaNative n, Path rom, Player[] all, ScheduledExecutorService net, long delayMs) {
			this.idx = idx; this.n = n; this.rom = rom; this.all = all; this.net = net; this.delayMs = delayMs;
		}

		void relay(byte[] m) {
			Player other = all[1 - idx];
			if (delayMs == 0) {
				LinkPump p = other.pump;
				if (p != null) p.offer(idx, m);
			} else {
				net.schedule(() -> { LinkPump p = other.pump; if (p != null) p.offer(idx, m); }, delayMs, TimeUnit.MILLISECONDS);
			}
		}

		@Override
		public void run() {
			try (GbaCore core = new GbaCore(n)) {
				if (core.loadRom(rom) < 0) throw new IllegalStateException("rom");
				LinkPump link = new LinkPump(this::relay, System::nanoTime, 5_000_000_000L, () -> {}, idx, 2, List.of("A", "B"));
				link.bind(core);
				link.apply(idx, 2, List.of("A", "B"));
				pump = link;
				BooleanSupplier stopped = () -> stop;
				long next = System.nanoTime();
				while (!stop) {
					core.setKeys(0);
					link.deliverIncoming();
					int r = link.runFrame(stopped, () -> {});
					if (r == GbaNative.RUN_ERROR) throw new IllegalStateException("core error");
					if (r == GbaNative.RUN_LINK_WAIT) break;
					frames++;
					long now = System.nanoTime();
					link.stats().tick(now);
					lastStats = link.stats().latest().format();
					next += FRAME_NANOS;
					// pacing: identical to EmulatorSession.loop
					while (!stop) {
						long wait = next - System.nanoTime();
						if (wait <= 0) break;
						if (link.count() >= 2) {
							if (link.awaitAndDeliver(wait > 1_000_000 ? wait - 500_000 : 0)) {
								if (next - System.nanoTime() <= FRAME_NANOS) break;
							} else {
								if (System.nanoTime() >= next) break;
								if (wait <= 1_000_000) Thread.onSpinWait();
							}
						}
					}
					long lag = System.nanoTime() - next;
					if (lag > 100_000_000L) next = System.nanoTime();
				}
				finalState = core.saveState();
			} catch (Throwable t) {
				failure = t;
			}
		}
	}

	public static Result run(GbaNative n, Path rom, long oneWayMs, long warmupMs, long measureMs) throws Exception {
		ScheduledExecutorService net = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "fake-net"); t.setDaemon(true); return t; });
		Player[] all = new Player[2];
		for (int i = 0; i < 2; i++) all[i] = new Player(i, n, rom, all, net, oneWayMs);
		Thread[] th = new Thread[2];
		for (int i = 0; i < 2; i++) { th[i] = new Thread(all[i], "baseline-" + i); th[i].setDaemon(true); th[i].start(); }
		Thread.sleep(warmupMs);
		long f0 = all[0].frames, f1 = all[1].frames;
		long t0 = System.nanoTime();
		Thread.sleep(measureMs);
		long t1 = System.nanoTime();
		long f0e = all[0].frames, f1e = all[1].frames;
		String line = all[0].lastStats;
		for (Player p : all) p.stop = true;
		for (Thread t : th) t.join(5000);
		net.shutdownNow();
		for (Player p : all) if (p.failure != null) throw new IllegalStateException("player failed", p.failure);
		lastChildState = all[1].finalState;
		double secs = (t1 - t0) / 1e9;
		LinkStats.Snapshot s = all[0].pump.stats().latest();
		return new Result(oneWayMs, (f0e - f0) / secs, (f1e - f1) / secs, s.waitPercent(), s.avgWaitMs(), s.exchangesPerSec(),
			SpikeSupport.Counters.of(all[0].finalState), SpikeSupport.Counters.of(all[1].finalState), line);
	}
}
