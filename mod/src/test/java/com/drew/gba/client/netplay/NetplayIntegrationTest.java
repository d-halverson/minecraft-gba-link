package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.spike.SpikeSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real cores, real {@link NetplayController}s on their own threads, a fake relay with one-way delay and jitter.
 * Skipped when the native library is not built for this platform. The ROM is the "pokesim" homebrew (one multiplayer
 * exchange per frame, validated sequence + checksum; counters in EWRAM).
 */
class NetplayIntegrationTest {
	@TempDir static Path tmp;
	private static GbaNative n;
	private static Path rom;
	static final StringBuilder REPORT = new StringBuilder();

	@BeforeAll
	static void load() throws IOException {
		n = SpikeSupport.loadNative(tmp);
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertNotNull(n, "native library missing");
		Assumptions.assumeTrue(n != null && n.supportsNetplay(), "native library (API 2) not built for " + GbaNative.platform());
		rom = SpikeSupport.extractRom(tmp.resolve("roms"), "link_pokesim.gba");
	}

	private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
		long end = System.nanoTime() + ms * 1_000_000L;
		while (System.nanoTime() < end) {
			if (c.getAsBoolean()) return;
			Thread.sleep(5);
		}
		fail("timeout waiting for " + what);
	}

	private static void rep(String s) {
		REPORT.append(s).append('\n');
		System.out.println(s);
	}

	record Result(double fps, long stalls, int delay, long bad, long missed, long desyncs) {}

	/** Run a session of {@code players} clients for {@code seconds} (after a 2 s start-up) and measure the first client. */
	private Result run(int players, long oneWay, long jitter, double seconds) throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, oneWay, jitter)) {
			List<NetplayHarness.Client> cs = new ArrayList<>();
			for (int i = 0; i < players; i++) cs.add(h.addClient());
			for (var c : cs) c.start();
			Thread.sleep(500); // standalone warm-up
			h.startSession(cs);
			await(() -> cs.stream().allMatch(c -> c.ctl != null && c.ctl.running() && c.ctl.engine().frame() > 5), 20_000, "all running");
			Thread.sleep(2000); // start-up phase excluded from the numbers
			var c0 = cs.get(0);
			long f0 = c0.ctl.engine().totalFrames, s0 = c0.ctl.engine().totalStalls;
			var base = new long[players][];
			for (int i = 0; i < players; i++) {
				var k = cs.get(i).counters(i);
				base[i] = new long[] {k.bad(), k.missed(), k.good()};
			}
			long t0 = System.nanoTime();
			Thread.sleep((long) (seconds * 1000));
			double secs = (System.nanoTime() - t0) / 1e9;
			long f1 = c0.ctl.engine().totalFrames, s1 = c0.ctl.engine().totalStalls;
			long bad = 0, missed = 0, good = 0;
			for (int i = 0; i < players; i++) {
				var k = cs.get(i).counters(i);
				bad += k.bad() - base[i][0];
				missed += k.missed() - base[i][1];
				good += k.good() - base[i][2];
			}
			long desyncs = cs.stream().mapToLong(c -> c.ctl.engine().desyncs).sum();
			for (var c : cs) assertNull(c.failure, "client " + c.id + " threw");
			// all clients must have computed identical hashes for every frame they both reached
			assertHashesAgree(h);
			Result r = new Result((f1 - f0) / secs, s1 - s0, c0.ctl.engine().delay(), bad, missed, desyncs);
			rep(String.format("netplay %d players, RTT %d ms (+-%d): %.1f fps, stalls %d, delay %df, ping %.0f ms, exchanges +%d good, bad %d, missed %d, desyncs %d",
				players, oneWay * 2, jitter, r.fps, r.stalls, r.delay, c0.ctl.engine().pingMs(), good, bad, missed, desyncs));
			return r;
		}
	}

	static void assertHashesAgree(NetplayHarness h) {
		Map<Long, Integer> ref = h.hashLog.get(0);
		int compared = 0;
		for (var e : h.hashLog.entrySet()) {
			for (var x : e.getValue().entrySet()) {
				Integer r = ref.get(x.getKey());
				if (r != null) {
					compared++;
					assertEquals(r, x.getValue(), "hash differs at gen/frame " + Long.toHexString(x.getKey()) + " client " + e.getKey());
				}
			}
		}
		assertTrue(compared > 5, "hashes compared: " + compared);
	}

	@Test
	void twoPlayersNoLatency() throws Exception {
		Result r = run(2, 0, 0, 6);
		assertTrue(r.fps >= 55, "fps " + r.fps);
		assertEquals(0, r.bad);
		assertEquals(0, r.missed);
		assertEquals(0, r.desyncs);
	}

	@Test
	void twoPlayers25msRtt() throws Exception {
		Result r = run(2, 12, 2, 6);
		assertTrue(r.fps >= 55, "fps " + r.fps);
		assertEquals(0, r.bad);
		assertEquals(0, r.missed);
		assertEquals(0, r.desyncs);
	}

	@Test
	void twoPlayers50msRtt() throws Exception {
		Result r = run(2, 25, 3, 8);
		assertTrue(r.fps >= 55, "fps " + r.fps);
		assertEquals(0, r.bad);
		assertEquals(0, r.missed);
		assertEquals(0, r.desyncs);
	}

	@Test
	void threePlayers50msRtt() throws Exception {
		Result r = run(3, 25, 3, 8);
		assertTrue(r.fps >= 55, "fps " + r.fps);
		assertEquals(0, r.bad);
		assertEquals(0, r.missed);
		assertEquals(0, r.desyncs);
	}

	private static List<NetplayHarness.Client> startAll(NetplayHarness h, int players) throws Exception {
		List<NetplayHarness.Client> cs = new ArrayList<>();
		for (int i = 0; i < players; i++) cs.add(h.addClient());
		for (var c : cs) c.start();
		Thread.sleep(400);
		h.startSession(cs);
		await(() -> cs.stream().allMatch(c -> c.ctl != null && c.ctl.running() && c.ctl.engine().frame() > 5), 20_000, "all running");
		return cs;
	}

	private void injectedDesync(int victim) throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 10, 2)) {
			var cs = startAll(h, 3);
			Thread.sleep(2500);
			cs.get(victim).post(() -> cs.get(victim).ctl.engine().debugCorrupt());
			await(() -> cs.stream().allMatch(c -> c.ctl.engine().resyncs >= 1), 15_000, "resync on every client");
			long desyncs = cs.stream().mapToLong(c -> c.ctl.engine().desyncs).sum();
			assertTrue(desyncs >= 1, "desync detected");
			int gen = cs.get(0).ctl.engine().generation();
			assertTrue(gen >= 1);
			Thread.sleep(4000); // several more hash checks after the resync
			for (var c : cs) assertNull(c.failure);
			// identical hashes for every check of the final generation
			int compared = 0;
			int finalGen = cs.stream().mapToInt(c -> c.ctl.engine().generation()).max().getAsInt();
			for (var e : h.hashLog.get(0).entrySet()) {
				if ((e.getKey() >> 32) != finalGen) continue;
				for (var other : h.hashLog.values()) {
					Integer o = other.get(e.getKey());
					if (o != null) { compared++; assertEquals(e.getValue(), o, "post-resync hash differs " + Long.toHexString(e.getKey())); }
				}
			}
			assertTrue(compared >= 6, "post-resync hashes compared: " + compared);
			for (var c : cs) assertTrue(c.ctl.engine().fps() > 50, "fps after resync " + c.ctl.engine().fps());
			rep("desync injected into P" + (victim + 1) + ": detected (" + desyncs + " mismatching checks), resynced " + cs.stream().mapToLong(c -> c.ctl.engine().resyncs).sum()
				+ " times, " + compared + " later hashes identical");
		}
	}

	@Test
	void injectedDesyncOfAChildIsDetectedAndResynced() throws Exception { injectedDesync(1); }

	@Test
	void injectedDesyncOfPlayerZeroIsDetectedAndResynced() throws Exception { injectedDesync(0); }

	@Test
	void aPlayerLeavingLetsTheOthersContinueAndTheLeaverGoesStandalone() throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 10, 2)) {
			var cs = startAll(h, 3);
			Thread.sleep(1500);
			var leaver = cs.get(2);
			var rest = List.of(cs.get(0), cs.get(1));
			// server: leaver closed its GBA -> the old session ends for the leaver, a new one starts for the others
			leaver.post(() -> leaver.ctl.end());
			long frames0 = leaver.standaloneFrames;
			h.startSession(rest);
			await(() -> rest.stream().allMatch(c -> c.ctl.engine() != null && c.ctl.engine().params().count() == 2 && c.ctl.running() && c.ctl.engine().frame() > 30), 20_000, "2 player session running");
			await(() -> leaver.standaloneFrames > frames0 + 60, 5000, "leaver runs standalone");
			assertFalse(leaver.ctl.active());
			Thread.sleep(2500);
			for (var c : cs) assertNull(c.failure);
			var k0 = rest.get(0).counters(0);
			var k1 = rest.get(1).counters(1);
			assertTrue(k0.good() > 100 && k1.good() > 100, k0 + " / " + k1);
			assertEquals(0, rest.get(0).ctl.engine().desyncs);
			for (var c : rest) assertTrue(c.ctl.engine().fps() > 55, "fps " + c.ctl.engine().fps());
			rep("player left a 3 player session: 2 continue at " + rest.get(0).ctl.engine().fps() + " fps, leaver standalone");
		}
	}

	@Test
	void aPlayerJoiningRestartsTheSessionWithEveryone() throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 10, 2)) {
			var two = new ArrayList<NetplayHarness.Client>();
			var c0 = h.addClient(); var c1 = h.addClient(); var c2 = h.addClient();
			c0.start(); c1.start(); c2.start();
			Thread.sleep(400);
			two.add(c0); two.add(c1);
			h.startSession(two);
			await(() -> c0.ctl.running() && c1.ctl.running() && c0.ctl.engine().frame() > 60, 20_000, "pair running");
			h.startSession(List.of(c0, c1, c2));
			await(() -> List.of(c0, c1, c2).stream().allMatch(c -> c.ctl.engine() != null && c.ctl.engine().params().count() == 3 && c.ctl.running() && c.ctl.engine().frame() > 90), 20_000, "trio running");
			Thread.sleep(1500);
			for (var c : List.of(c0, c1, c2)) { assertNull(c.failure); assertEquals(0, c.ctl.engine().desyncs); }
		}
	}

	@Test
	void highLatencyRaisesTheDelayAndKeepsFullSpeed() throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 60, 5)) { // RTT 120 ms +- 10
			var cs = startAll(h, 2);
			Thread.sleep(12_000); // adaptation window
			long f0 = cs.get(0).ctl.engine().totalFrames, s0 = cs.get(0).ctl.engine().totalStalls;
			long t0 = System.nanoTime();
			Thread.sleep(6000);
			double fps = (cs.get(0).ctl.engine().totalFrames - f0) / ((System.nanoTime() - t0) / 1e9);
			long stalls = cs.get(0).ctl.engine().totalStalls - s0;
			int delay = cs.get(0).ctl.engine().delay();
			rep(String.format("RTT 120 ms +-10: delay settled at %df, %.1f fps, %d stalls in 6 s", delay, fps, stalls));
			assertTrue(delay >= 4 && delay <= 10, "delay " + delay);
			assertTrue(fps >= 55, "fps " + fps);
			assertEquals(0, cs.get(0).ctl.engine().desyncs);
		}
	}

	@Test
	void sessionWithoutPartnersGivesUpAfterTheConnectTimeout() throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 0, 0)) {
			var a = h.addClient(); var b = h.addClient();
			a.start();
			Thread.sleep(300);
			// only A gets the session start; B never answers
			a.members = List.of(a, b);
			a.post(() -> a.ctl.start(new NetplayEngine.Params(99, 0, 2, List.of("a", "b"), 1_760_000_000L, 0, 3)));
			await(() -> a.ctl.active(), 2000, "connecting");
			assertFalse(a.ctl.running());
			await(() -> !a.ctl.active(), 20_000, "fallback to standalone");
			long f = a.standaloneFrames;
			await(() -> a.standaloneFrames > f + 30, 3000, "standalone frames");
			assertTrue(a.events.stream().anyMatch(e -> e.startsWith("left: ")), a.events.toString());
		}
	}
}
