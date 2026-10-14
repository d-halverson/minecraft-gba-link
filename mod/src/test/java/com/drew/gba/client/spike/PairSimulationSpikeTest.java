package com.drew.gba.client.spike;

import com.drew.gba.client.emu.GbaNative;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feasibility spike for deterministic dual-simulation link play (see the Task B notes in the repo history / report).
 * Test sources only; the runtime is not touched. Skipped when libgbacore is not built for this platform.
 * Numbers are printed and collected into build/spike-report.txt. GBALINK_SPIKE_LONG=1 uses longer measurement windows.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PairSimulationSpikeTest {
	@TempDir static Path tmp;
	private static GbaNative n;
	private static Path rom;
	private static final StringBuilder REPORT = new StringBuilder();
	private static final boolean LONG = System.getenv("GBALINK_SPIKE_LONG") != null;

	@BeforeAll
	static void load() throws IOException {
		n = SpikeSupport.loadNative(tmp);
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertNotNull(n, "native library missing");
		Assumptions.assumeTrue(n != null, "native library not built for " + GbaNative.platform());
		rom = SpikeSupport.extractRom(tmp.resolve("roms"), "link_pokesim.gba");
	}

	@AfterAll
	static void write() throws IOException {
		Path out = Path.of("build", "spike-report.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, REPORT.toString());
	}

	private static void rep(String s) {
		REPORT.append(s).append('\n');
		System.out.println(s);
	}

	// ------------------------------------------------------------------ B1: the test ROM itself

	@Test @Order(1)
	void pokesimRomValidatesAnExchangePerFrame() {
		try (DualSim d = new DualSim(n, rom, 3, 1, false)) {
			for (int i = 0; i < 600; i++) assertTrue(d.tick());
			SpikeSupport.Counters m = SpikeSupport.Counters.of(d.c0.saveState()), c = SpikeSupport.Counters.of(d.c1.saveState());
			assertTrue(m.good() >= 590, m.toString());
			assertTrue(c.good() >= 590, c.toString());
			assertEquals(0, m.bad() + m.missed() + c.bad() + c.missed());
			assertEquals(0, m.errors() + c.errors());
			assertEquals(1, m.role());
			assertEquals(0, c.role());
			rep("B1 pokesim ROM, 600 frames dual-sim: master " + m + " | child " + c);
		}
	}

	// ------------------------------------------------------------------ B2: baseline = current lockstep over a fake network

	@Test @Order(2)
	void baselineLockstepReproducesTheRoundTripLaw() throws Exception {
		rep("B2 baseline (current lockstep, two threads, one-way delay -> master fps; model A = 1/(1/59.73+RTT), model B = 1/(RTT+5ms+1ms)):");
		rep("  one-way  RTT   master fps  child fps  xchg/s  wait%  avg wait ms  modelA  modelB  child counters");
		double[] fps = new double[4];
		long[] delays = {0, 10, 25, 50};
		for (int i = 0; i < delays.length; i++) {
			var r = LockstepBaseline.run(n, rom, delays[i], LONG ? 2000 : 1000, LONG ? 8000 : 3000);
			double rtt = delays[i] * 2;
			fps[i] = r.masterFps();
			rep(String.format("  %5d ms %4.0f   %9.1f  %9.1f  %6.0f  %5.0f  %10.1f  %6.1f  %6.1f  good=%d bad=%d missed=%d   | master good=%d bad=%d err=%d",
				delays[i], rtt, r.masterFps(), r.childFps(), r.xchgPerSec(), r.masterWaitPct(), r.avgWaitMs(),
				1000.0 / (1000.0 / 59.7275 + rtt), Math.min(59.7275, 1000.0 / (rtt + 6)), r.child().good(), r.child().bad(), r.child().missed(),
				r.master().good(), r.master().bad(), r.master().err()));
		}
		assertTrue(fps[0] > 55, "no delay should run at full speed: " + fps[0]);
		assertTrue(fps[3] < 15, "RTT 100 ms must be slow: " + fps[3]);
		assertTrue(fps[1] > fps[2] && fps[2] > fps[3]);
	}

	// ------------------------------------------------------------------ B3: dual simulation

	@Test @Order(3)
	void dualSimRunsCleanAtFullSpeed() {
		byte[] fb = new byte[240 * 160 * 4];
		int frames = 5000;
		try (DualSim d = new DualSim(n, rom, 4, 7, false)) {
			d.drainAudio = true;
			for (int i = 0; i < 300; i++) d.tick(); // JIT warm-up
		}
		try (DualSim d = new DualSim(n, rom, 4, 7, false)) {
			d.drainAudio = true;
			long t0 = System.nanoTime();
			for (int i = 0; i < frames; i++) assertTrue(d.tick());
			long t1 = System.nanoTime();
			double msPerFrame = (t1 - t0) / 1e6 / frames;
			SpikeSupport.Counters m = SpikeSupport.Counters.of(d.c0.saveState()), c = SpikeSupport.Counters.of(d.c1.saveState());
			rep(String.format("B3a dual-sim %d frames (input delay 4): master %s | child %s", frames, m, c));
			rep(String.format("B3c cost: %.3f ms per frame for BOTH cores incl. audio drain and relay (budget 16.7 ms) = %.0fx real time; link waits %d (all resolved without waiting on a network), child extra runs %d, messages %d",
				msPerFrame, 1000.0 / 59.7275 / msPerFrame, d.linkWaits, d.childRuns - frames, d.relayedMsgs));
			assertEquals(0, m.bad() + m.missed() + c.bad() + c.missed(), "bad/missed exchanges");
			assertEquals(0, m.errors() + c.errors());
			assertTrue(m.good() >= frames - 5 && c.good() >= frames - 5);
			assertTrue(msPerFrame < 8.0, "two cores must fit comfortably in a frame: " + msPerFrame);
		}
	}

	@Test @Order(4)
	void dualSimIsDeterministicInProcessAndAcrossJvms() throws Exception {
		String a = DualSimRunner.run(n, rom, 3, 42, false, true);
		String b = DualSimRunner.run(n, rom, 3, 42, false, true);
		assertEquals(a, b, "two runs in this JVM");
		// two separate JVM processes
		Path gd1 = tmp.resolve("jvm1"), gd2 = tmp.resolve("jvm2");
		String o1 = runInChildJvm(gd1), o2 = runInChildJvm(gd2);
		assertEquals(o1, o2, "two separate JVM processes");
		assertEquals(a, o1, "this JVM vs a fresh process");
		rep("B3b determinism: identical frame hashes (framebuffers, EWRAM+IWRAM+VRAM, full 388 KiB state; both cores) at frames 100/1000/5000 in-process x2 and in 2 separate JVMs:");
		for (String l : a.split("\n")) rep("  " + l);
		// different inputs must give different results (the hashes are sensitive)
		String other = DualSimRunner.run(n, rom, 3, 43, false, true);
		assertNotEquals(a, other, "a different input script must change the state");
	}

	private static String runInChildJvm(Path gameDir) throws Exception {
		Path out = gameDir.resolveSibling(gameDir.getFileName() + ".out");
		Files.createDirectories(gameDir);
		String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		ProcessBuilder pb = new ProcessBuilder(javaBin, "--enable-native-access=ALL-UNNAMED", "-Xmx300m", "-cp", System.getProperty("java.class.path"),
			"com.drew.gba.client.spike.DualSimRunner", gameDir.toString(), out.toString());
		pb.redirectErrorStream(true);
		pb.redirectOutput(gameDir.resolveSibling(gameDir.getFileName() + ".log").toFile());
		Process p = pb.start();
		assertTrue(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "child JVM timed out");
		assertEquals(0, p.exitValue(), "child JVM failed: " + Files.readString(gameDir.resolveSibling(gameDir.getFileName() + ".log")));
		return Files.readString(out);
	}

	@Test @Order(5)
	void stateCostAndSnapshotResume() {
		try (DualSim d = new DualSim(n, rom, 3, 5, false)) {
			for (int i = 0; i < 500; i++) d.tick();
			int iters = 300;
			byte[] st = null;
			for (int i = 0; i < 50; i++) st = d.c0.saveState();
			long t0 = System.nanoTime();
			for (int i = 0; i < iters; i++) st = d.c0.saveState();
			double saveMs = (System.nanoTime() - t0) / 1e6 / iters;
			for (int i = 0; i < 50; i++) d.c0.loadState(st);
			t0 = System.nanoTime();
			for (int i = 0; i < iters; i++) assertTrue(d.c0.loadState(st));
			double loadMs = (System.nanoTime() - t0) / 1e6 / iters;
			t0 = System.nanoTime();
			long h = 0;
			for (int i = 0; i < iters; i++) h ^= SpikeSupport.memHash(st);
			double hashMs = (System.nanoTime() - t0) / 1e6 / iters;
			t0 = System.nanoTime();
			for (int i = 0; i < iters; i++) h ^= SpikeSupport.crc(st, 0, st.length);
			double fullMs = (System.nanoTime() - t0) / 1e6 / iters;
			java.util.zip.Deflater df = new java.util.zip.Deflater(1);
			df.setInput(st);
			df.finish();
			byte[] buf = new byte[st.length];
			t0 = System.nanoTime();
			int comp = df.deflate(buf);
			double zMs = (System.nanoTime() - t0) / 1e6;
			rep(String.format("B3c state: %d bytes (%.0f KiB) per console (EWRAM 256K, VRAM 96K, IWRAM 32K, rest 4K...); save %.3f ms, load %.3f ms, memHash(EWRAM+IWRAM+VRAM) %.3f ms, full CRC32 %.3f ms; deflate-1: %d bytes (%.1f%%) in %.1f ms; a 2-console link-start snapshot = %.0f KiB raw. (hash sink %d)",
				st.length, st.length / 1024.0, saveMs, loadMs, hashMs, fullMs, comp, 100.0 * comp / st.length, zMs, 2 * st.length / 1024.0, h & 1));
			assertEquals(SpikeSupport.STATE_SIZE, st.length);
		}
	}

	/**
	 * Link-start snapshot: A keeps its running cores, B loads A's snapshot into brand-new cores. The shim keeps a little link
	 * state that is NOT in the save state (child "ranSinceDone", pending flags, peer modes), so this diverges at the first
	 * exchange. If both sides load the snapshot into fresh cores they stay identical.
	 */
	@Test @Order(6)
	void snapshotResumeFreshCoresOnBothSidesStaysIdentical() {
		byte[] fb = new byte[240 * 160 * 4];
		byte[][] snap;
		try (DualSim a = new DualSim(n, rom, 3, 9, false)) {
			for (int i = 0; i < 500; i++) a.tick();
			snap = a.snapshot();
			try (DualSim b = new DualSim(n, rom, 3, 9, false)) {
				assertTrue(b.restore(snap, 500));
				int firstDiff = -1;
				for (int i = 500; i < 1500; i++) {
					a.tick(); b.tick();
					if (firstDiff < 0 && !DualSimRunner.hashes(a, fb, i).equals(DualSimRunner.hashes(b, fb, i))) firstDiff = i;
				}
				rep("B3d snapshot at frame 500: A keeps its running cores, B loads it into fresh cores -> first divergence at frame " + (firstDiff < 0 ? "none" : firstDiff)
					+ " (shim link state outside the save state: ranSinceDone / pending flags / peer modes)");
				assertTrue(firstDiff > 0, "documented shim limitation: expected a divergence");
			}
		}
		try (DualSim a = new DualSim(n, rom, 3, 9, false); DualSim b = new DualSim(n, rom, 3, 9, false)) {
			assertTrue(a.restore(snap, 500));
			assertTrue(b.restore(snap, 500));
			int firstDiff = -1;
			for (int i = 500; i < 5500; i++) {
				a.tick(); b.tick();
				if (i % 50 == 0 && firstDiff < 0 && !DualSimRunner.hashes(a, fb, i).equals(DualSimRunner.hashes(b, fb, i))) firstDiff = i;
			}
			SpikeSupport.Counters m = SpikeSupport.Counters.of(a.c0.saveState()), c = SpikeSupport.Counters.of(a.c1.saveState());
			rep("B3d both sides load the snapshot into FRESH cores: first divergence over 5000 frames = " + (firstDiff < 0 ? "none" : firstDiff) + "; counters master " + m + " | child " + c);
			assertEquals(-1, firstDiff);
			assertTrue(m.bad() + m.missed() + c.bad() + c.missed() <= 1, "at most one irregular exchange at the load point");
		}
	}

	/** Resync experiment: corrupt one console on machine B only; hash every 60 frames; detect; recover from A's snapshot. */
	@Test @Order(7)
	void resyncDetectsCorruptionWithinOneSecond() {
		byte[] fb = new byte[240 * 160 * 4];
		int corruptAt = 1234;
		record Case(String name, int console, int stateOffset, int bit) {}
		List<Case> cases = List.of(
			new Case("EWRAM (game variable, core0 my-seq)", 0, SpikeSupport.EWRAM + SpikeSupport.V_SEQ, 3),
			new Case("EWRAM (core1 rolling hash)", 1, SpikeSupport.EWRAM + SpikeSupport.V_HASH, 17),
			new Case("VRAM (tile data)", 0, SpikeSupport.VRAM + 40, 0),
			new Case("IWRAM (stack area)", 1, SpikeSupport.IWRAM + 0x7F00, 5));
		for (Case cs : cases) {
			try (DualSim a = new DualSim(n, rom, 3, 11, false); DualSim b = new DualSim(n, rom, 3, 11, false)) {
				int detected = -1, healedAt = -1;
				long mismatchedChecks = 0;
				for (int f = 1; f <= corruptAt + 400; f++) {
					a.tick(); b.tick();
					if (f == corruptAt) {
						byte[][] s = b.snapshot();
						s[cs.console()][cs.stateOffset() + cs.bit() / 8] ^= (byte) (1 << (cs.bit() % 8));
						assertTrue(b.restore(s, f));
					}
					if (f % 60 == 0) {
						boolean same = SpikeSupport.memHash(a.c0.saveState()) == SpikeSupport.memHash(b.c0.saveState())
							&& SpikeSupport.memHash(a.c1.saveState()) == SpikeSupport.memHash(b.c1.saveState());
						if (f >= corruptAt && !same) { mismatchedChecks++; if (detected < 0) detected = f; }
					}
				}
				rep(String.format("B3e resync: bit flip in %s at frame %d on machine B only -> first mismatching hash check at frame %d (%s%d frames = %.2f s after the flip; checks every 60 frames, +RTT/2 for the hash to arrive); mismatching checks afterwards: %d",
					cs.name(), corruptAt, detected, detected < 0 ? "NOT DETECTED, " : "", detected < 0 ? -1 : detected - corruptAt, detected < 0 ? -1 : (detected - corruptAt) / 59.7275, mismatchedChecks));
				assertTrue(detected > 0 || cs.name().startsWith("IWRAM") || cs.name().startsWith("VRAM"), "EWRAM corruption must be detected: " + cs);
				if (detected > 0) assertTrue(detected - corruptAt <= 60, "detected within 60 frames");
			}
		}
		// recovery: both sides load the same snapshot (standard netplay), into fresh cores
		try (DualSim a = new DualSim(n, rom, 3, 11, false); DualSim b = new DualSim(n, rom, 3, 11, false)) {
			for (int f = 1; f <= 1000; f++) { a.tick(); b.tick(); }
			byte[][] s = b.snapshot();
			s[0][SpikeSupport.EWRAM + SpikeSupport.V_SEQ] ^= 8;
			b.restore(s, 1000); // B is now corrupted
			byte[][] good = a.snapshot();
			long t0 = System.nanoTime();
			try (DualSim a2 = new DualSim(n, rom, 3, 11, false); DualSim b2 = new DualSim(n, rom, 3, 11, false)) {
				double newMs = (System.nanoTime() - t0) / 1e6 / 2;
				assertTrue(a2.restore(good, 1000));
				assertTrue(b2.restore(good, 1000));
				int firstDiff = -1;
				for (int f = 1001; f <= 3000; f++) {
					a2.tick(); b2.tick();
					if (firstDiff < 0 && !DualSimRunner.hashes(a2, fb, f).equals(DualSimRunner.hashes(b2, fb, f))) firstDiff = f;
				}
				rep(String.format("B3e recovery: both sides drop their cores (%.1f ms to create+load a console), load A's snapshot at frame 1000 -> first divergence = %s", newMs,
					firstDiff < 0 ? "none in 2000 frames (resync works)" : String.valueOf(firstDiff)));
				assertTrue(firstDiff < 0, "resync via snapshot must hold");
			}
		}
	}

	// ------------------------------------------------------------------ B4: what breaks determinism

	@Test @Order(8)
	void determinismProbes() throws Exception {
		String ref = DualSimRunner.run(n, rom, 3, 42, false, true);
		String noDrain = DualSimRunner.run(n, rom, 3, 42, false, false);
		rep("B4 audio draining (real client reads audio every frame) vs never draining: " + (ref.equals(noDrain) ? "identical state" : "DIFFERENT"));
		assertEquals(ref, noDrain);
		// thread scheduling / CPU contention / pauses between ticks
		List<Thread> burners = new ArrayList<>();
		volatileStop = false;
		for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
			Thread t = new Thread(() -> { double x = 1; while (!volatileStop) x = Math.sqrt(x + 1); sink = x; });
			t.setDaemon(true); t.start(); burners.add(t);
		}
		String noisy;
		try {
			StringBuilder sb = new StringBuilder();
			byte[] fb = new byte[240 * 160 * 4];
			java.util.Random r = new java.util.Random(1);
			try (DualSim d = new DualSim(n, rom, 3, 42, false)) {
				d.drainAudio = true;
				int next = 0;
				for (int f = 1; f <= 5000; f++) {
					d.tick();
					if (r.nextInt(50) == 0) { if (r.nextBoolean()) Thread.sleep(r.nextInt(5)); else Thread.yield(); }
					if (f == DualSimRunner.CHECK_FRAMES[next]) { sb.append(DualSimRunner.hashes(d, fb, f)).append('\n'); next++; }
				}
			}
			noisy = sb.toString();
		} finally {
			volatileStop = true;
		}
		rep("B4 scheduling noise (all CPUs busy, random sleeps/yields between ticks): " + (ref.equals(noisy) ? "identical state" : "DIFFERENT"));
		assertEquals(ref, noisy);
		// execution order is part of the protocol
		String childFirst = DualSimRunner.run(n, rom, 3, 42, true, true);
		String childFirst2 = DualSimRunner.run(n, rom, 3, 42, true, true);
		rep("B4 stepping order (child before master): deterministic by itself = " + childFirst.equals(childFirst2) + ", but differs from master-first = " + !childFirst.equals(ref)
			+ " -> the order must be fixed by the protocol (it is the same code, but any change must be versioned)");
		assertEquals(childFirst, childFirst2);
		assertNotEquals(childFirst, ref);
		// input delay changes behaviour (must be agreed)
		assertNotEquals(ref, DualSimRunner.run(n, rom, 5, 42, false, true));
	}

	static volatile boolean volatileStop;
	static volatile double sink;

	// ------------------------------------------------------------------ B5: input delay model

	@Test @Order(9)
	void inputDelayModel() {
		int frames = LONG ? 200_000 : 60_000;
		rep("B5 input-delay model (inputs only, TCP-like in-order delivery, one-way = RTT/2 +/- jitter, 2.5 ms/frame emulation cost, " + frames + " frames = " + Math.round(frames / 59.7275 / 60) + " min simulated per cell):");
		rep("  RTT ms  jitter  min d for <=1% stalled frames  (input lag)   min d for <=0.1%  (input lag)   min d for 0 stalls over the run   | with 0.5% retransmit spikes: d for <=1%");
		double[] rtts = {20, 50, 100};
		for (double rtt : rtts) {
			for (double j : new double[] {0, 10}) {
				int d1 = InputDelayModel.minDelay(rtt, j, 0.01, frames, 0, 5);
				int d01 = InputDelayModel.minDelay(rtt, j, 0.001, frames, 0, 5);
				int d0 = InputDelayModel.minDelay(rtt, j, 0.0, frames, 0, 5);
				int dsp = InputDelayModel.minDelay(rtt, j, 0.01, frames, 0.005, 5);
				rep(String.format("  %6.0f  +-%2.0f    %2d                             (%5.0f ms)       %2d                (%5.0f ms)       %2d                                 | %2d (%.0f ms)",
					rtt, j, d1, d1 * InputDelayModel.P, d01, d01 * InputDelayModel.P, d0, dsp, dsp * InputDelayModel.P));
			}
		}
		rep("  stall profile at jitter +-10 ms for a few delays (stall = frame that had to wait for the partner's input):");
		for (double rtt : rtts) {
			int[] ds = rtt == 20 ? new int[] {1, 2, 3, 4} : rtt == 50 ? new int[] {2, 3, 4, 5, 6} : new int[] {3, 4, 5, 6, 8};
			for (int d : ds) {
				var r = InputDelayModel.simulate(rtt, 10, d, frames, 0, 5, 2.5);
				rep(String.format("    RTT %3.0f d=%d (%4.0f ms lag): stalled frames %.3f%%, mean stall %.1f ms, p99 %.1f ms, max %.1f ms, effective %.2f fps, stalls >2 frames: %.2f/min",
					rtt, d, d * InputDelayModel.P, r.stallRate() * 100, r.meanStallMs(), r.p99StallMs(), r.maxStallMs(), r.effectiveFps(), r.longStallsPerMin()));
			}
		}
		// sanity: more delay never increases stalls; zero latency has none
		assertEquals(0.0, InputDelayModel.simulate(0, 0, 2, 5000, 0, 1, 2.5).stallRate(), 1e-9);
		assertTrue(InputDelayModel.simulate(50, 10, 1, 20000, 0, 1, 2.5).stallRate() > InputDelayModel.simulate(50, 10, 8, 20000, 0, 1, 2.5).stallRate());
	}
}
