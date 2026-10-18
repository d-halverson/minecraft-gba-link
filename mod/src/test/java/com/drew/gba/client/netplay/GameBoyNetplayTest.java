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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Input-sync netplay of two Game Boy consoles (native API 5, GBSIOLockstep): real cores and {@link NetplayController}s on their
 * own threads, a fake relay with latency. The ROM is the serial exerciser gblink_auto.gb of native/tests/make_gb_link_rom.py:
 * a console becomes the link master when its A button is pressed, then bytes flow every frame (log at C200, count at C111).
 */
class GameBoyNetplayTest {
	@TempDir static Path tmp;
	private static GbaNative n;
	private static Path rom;
	private static final int A = 1;

	@BeforeAll
	static void load() throws IOException {
		n = SpikeSupport.loadNative(tmp);
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertNotNull(n, "native library missing");
		Assumptions.assumeTrue(n != null && n.supportsGameBoyLink(), "native library (API 5) not built for " + GbaNative.platform());
		rom = SpikeSupport.extractRom(tmp.resolve("roms"), "gblink_auto.gb");
	}

	private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
		long end = System.nanoTime() + ms * 1_000_000L;
		while (System.nanoTime() < end) {
			if (c.getAsBoolean()) return;
			Thread.sleep(5);
		}
		fail("timeout waiting for " + what);
	}

	private static int peek(byte[] st, int addr) { return st[0x4400 + addr - 0xC000] & 0xFF; }

	private static int transfers(NetplayHarness.Client c, int console) throws Exception {
		return peek(c.call(ctl -> ctl.engine().sim().core(console).saveState()), 0xC111);
	}

	private void run(long oneWay, long jitter, int seconds) throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, oneWay, jitter)) {
			List<NetplayHarness.Client> cs = new ArrayList<>();
			for (int i = 0; i < 2; i++) cs.add(h.addClient());
			for (var c : cs) c.start();
			Thread.sleep(500);
			h.startSession(cs);
			await(() -> cs.stream().allMatch(c -> c.ctl != null && c.ctl.running() && c.ctl.engine().frame() > 5), 20_000, "all running");
			// press A on player 1 only: its console becomes the master and the bytes start to flow in both simulations
			cs.get(1).keysHeld = A;
			await(() -> {
				try { return transfers(cs.get(0), 0) >= 30 && transfers(cs.get(1), 1) >= 30; } catch (Exception e) { return false; }
			}, 20_000, "link transfers");
			cs.get(1).keysHeld = 0;
			Thread.sleep(seconds * 1000L);
			for (var c : cs) assertNull(c.failure, "client " + c.id + " threw");
			assertEquals(0, cs.get(0).ctl.engine().desyncs + cs.get(1).ctl.engine().desyncs, "desyncs");
			NetplayIntegrationTest.assertHashesAgree(h);
			// both simulations on one client agree on what each console sent and received
			byte[] s0 = cs.get(0).call(ctl -> ctl.engine().sim().core(0).saveState());
			byte[] s1 = cs.get(0).call(ctl -> ctl.engine().sim().core(1).saveState());
			int count = Math.min(peek(s0, 0xC111), 100);
			assertTrue(count >= 30, "transfers " + count);
			for (int k = 0; k < count; k++) {
				assertEquals(peek(s1, 0xC200 + 2 * k), peek(s0, 0xC201 + 2 * k), "P1 received what P2 sent, transfer " + k);
				assertEquals(peek(s0, 0xC200 + 2 * k), peek(s1, 0xC201 + 2 * k), "P2 received what P1 sent, transfer " + k);
			}
			// (the two clients hold the same consoles bit for bit: assertHashesAgree compared their state hashes every second)
			for (var c : cs) assertTrue(c.ctl.engine().fps() > 55, "fps " + c.ctl.engine().fps());
		}
	}

	@Test
	void twoGameBoysLinkedWithoutLatency() throws Exception { run(0, 0, 3); }

	@Test
	void twoGameBoysLinkedAt50msRtt() throws Exception { run(25, 3, 6); }

	@Test
	void injectedDesyncOfAGameBoyIsDetectedAndResynced() throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 10, 2)) {
			List<NetplayHarness.Client> cs = new ArrayList<>();
			for (int i = 0; i < 2; i++) cs.add(h.addClient());
			for (var c : cs) c.start();
			Thread.sleep(500);
			h.startSession(cs);
			await(() -> cs.stream().allMatch(c -> c.ctl != null && c.ctl.running() && c.ctl.engine().frame() > 5), 20_000, "all running");
			cs.get(1).keysHeld = A;
			Thread.sleep(2500);
			cs.get(1).post(() -> cs.get(1).ctl.engine().debugCorrupt());
			await(() -> cs.stream().allMatch(c -> c.ctl.engine().resyncs >= 1), 15_000, "resync on every client");
			assertTrue(cs.stream().mapToLong(c -> c.ctl.engine().desyncs).sum() >= 1);
			Thread.sleep(4000);
			for (var c : cs) assertNull(c.failure);
			int finalGen = cs.stream().mapToInt(c -> c.ctl.engine().generation()).max().getAsInt();
			int compared = 0;
			for (var e : h.hashLog.get(0).entrySet()) {
				if ((e.getKey() >> 32) != finalGen) continue;
				for (var other : h.hashLog.values()) {
					Integer o = other.get(e.getKey());
					if (o != null) { compared++; assertEquals(e.getValue(), o, "post-resync hash differs " + Long.toHexString(e.getKey())); }
				}
			}
			assertTrue(compared >= 6, "post-resync hashes compared: " + compared);
		}
	}

	@Test
	void aPlayerLeavingReturnsToAStandaloneGameBoy() throws Exception {
		try (NetplayHarness h = new NetplayHarness(n, rom, 5, 1)) {
			List<NetplayHarness.Client> cs = new ArrayList<>();
			for (int i = 0; i < 2; i++) cs.add(h.addClient());
			for (var c : cs) c.start();
			Thread.sleep(500);
			h.startSession(cs);
			await(() -> cs.stream().allMatch(c -> c.ctl != null && c.ctl.running() && c.ctl.engine().frame() > 30), 20_000, "all running");
			var leaver = cs.get(1);
			leaver.post(() -> leaver.ctl.end());
			long f0 = leaver.standaloneFrames;
			await(() -> leaver.standaloneFrames > f0 + 60, 5000, "leaver runs standalone");
			assertFalse(leaver.ctl.active());
			assertTrue(leaver.ctl.core().isGameBoy());
			for (var c : cs) assertNull(c.failure);
		}
	}
}
