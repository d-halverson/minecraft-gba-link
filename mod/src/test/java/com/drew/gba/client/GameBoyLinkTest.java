package com.drew.gba.client;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaGroup;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.emu.LinkPump;
import com.drew.gba.server.EffectiveLink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Game Boy / Game Boy Color link (native API 5) through the mod's own binding: two consoles in a lockstep group (same ROM
 * netplay core) and the classic byte link through {@link LinkPump} (different ROMs), with the serial exerciser ROMs from
 * native/tests/make_gb_link_rom.py (WRAM contract in its docstring). Skipped when the native library is missing.
 */
class GameBoyLinkTest {
	/** Offset of work RAM C000 inside mGBA's Game Boy save state. */
	static final int WRAM = 0x4400;
	@TempDir static Path tmp;
	private static GbaNative n;
	private static Path roms;

	@BeforeAll
	static void load() throws IOException {
		String platform = GbaNative.platform();
		String lib = platform.startsWith("macos") ? "libgbacore.dylib" : platform.startsWith("windows") ? "gbacore.dll" : "libgbacore.so";
		Path src = Path.of("..", "dist", "natives", platform, lib).toAbsolutePath().normalize();
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertTrue(Files.isRegularFile(src), "native library missing: " + src);
		Assumptions.assumeTrue(Files.isRegularFile(src), "native library not built for " + platform);
		Path dir = tmp.resolve("gbalink").resolve("natives").resolve(platform);
		Files.createDirectories(dir);
		Files.copy(src, dir.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
		n = GbaNative.get(tmp);
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertNotNull(n, "native library failed to load: " + GbaNative.loadError());
		Assumptions.assumeTrue(n != null, "native library failed to load: " + GbaNative.loadError());
		Assumptions.assumeTrue(n.supportsGameBoyLink(), "native library without Game Boy link (API 5)");
		roms = tmp.resolve("roms");
		Files.createDirectories(roms);
		for (String r : new String[] {"gblink_dmg.gb", "gblink_dmg_b.gb", "gblink_cgb.gbc"}) {
			try (InputStream in = GameBoyLinkTest.class.getResourceAsStream("/roms/" + r)) {
				Files.copy(in, roms.resolve(r));
			}
		}
	}

	private static GbaCore booted(String rom) {
		GbaCore c = new GbaCore(n);
		assertEquals(0, c.loadRom(roms.resolve(rom)), rom);
		short[] pcm = new short[4096];
		for (int i = 0; i < 6; i++) { c.runFrame(); while (c.readAudio(pcm, 2048) > 0) { } }
		return c;
	}

	/** Write bytes into work RAM through a save-state round trip (the ABI has no memory access). */
	static void poke(GbaCore c, int addr, int... bytes) {
		byte[] st = c.saveState();
		for (int i = 0; i < bytes.length; i++) st[WRAM + (addr - 0xC000) + i] = (byte) bytes[i];
		assertTrue(c.loadState(st));
	}

	static int peek(byte[] st, int addr) { return st[WRAM + (addr - 0xC000)] & 0xFF; }

	/** role, base, interval, SC value (see the ROM's docstring) */
	static void configure(GbaCore c, int role, int base, int interval, int sc) {
		poke(c, 0xC100, role, 0, base, interval);
		poke(c, 0xC119, sc);
	}

	@Test
	void apiVersionAndCapabilities() {
		assertEquals(5, n.apiVersion());
		assertTrue(n.supportsGameBoy());
		assertTrue(n.supportsNetplay());
		assertTrue(n.supportsGameBoyLink());
	}

	private static void verify(byte[] a, byte[] b, int count, int baseA, int baseB) {
		assertTrue(peek(a, 0xC111) >= count && peek(b, 0xC111) >= count, "transfers " + peek(a, 0xC111) + " / " + peek(b, 0xC111));
		for (int k = 0; k < count; k++) {
			assertEquals((baseA + k) & 0xFF, peek(a, 0xC200 + 2 * k), "A sent " + k);
			assertEquals((baseB + k) & 0xFF, peek(b, 0xC200 + 2 * k), "B sent " + k);
			assertEquals(peek(b, 0xC200 + 2 * k), peek(a, 0xC201 + 2 * k), "A received " + k);
			assertEquals(peek(a, 0xC200 + 2 * k), peek(b, 0xC201 + 2 * k), "B received " + k);
		}
	}

	@Test
	void lockstepGroupExchangesBytesBothWays() {
		for (String[] roms : new String[][] {{"gblink_dmg.gb", "gblink_dmg.gb"}, {"gblink_dmg.gb", "gblink_cgb.gbc"}, {"gblink_cgb.gbc", "gblink_dmg_b.gb"}}) {
			try (GbaCore a = booted(roms[0]); GbaCore b = booted(roms[1])) {
				configure(a, 1, 0x10, 1, 0x81);
				configure(b, 2, 0xA0, 1, 0x81);
				try (GbaGroup g = new GbaGroup(n, a, b)) {
					short[] pcm = new short[4096];
					for (int f = 0; f < 80; f++) {
						assertEquals(GbaNative.RUN_FRAME_DONE, g.runFrame());
						while (a.readAudio(pcm, 2048) > 0) { }
						while (b.readAudio(pcm, 2048) > 0) { }
					}
					verify(a.saveState(), b.saveState(), 40, 0x10, 0xA0);
				}
			}
		}
	}

	@Test
	void groupsRefuseMixedPlatformsAndOversizedGameBoyGroups() {
		try (GbaCore gb = booted("gblink_dmg.gb"); GbaCore gb2 = booted("gblink_dmg.gb"); GbaCore gb3 = booted("gblink_dmg.gb")) {
			assertThrows(IllegalStateException.class, () -> new GbaGroup(n, gb, gb2, gb3), "a Game Boy cable joins two consoles");
		}
		Path gba = tmp.resolve("roms").resolve("counter.gba");
		try (GbaCore a = booted("gblink_dmg.gb")) {
			GbaCore c = new GbaCore(n);
			try {
				Files.copy(GameBoyLinkTest.class.getResourceAsStream("/roms/counter.gba"), gba, StandardCopyOption.REPLACE_EXISTING);
				assertEquals(0, c.loadRom(gba));
				assertThrows(IllegalStateException.class, () -> new GbaGroup(n, a, c), "Game Boy and GBA never share a group");
			} catch (IOException e) {
				fail(e);
			} finally {
				c.close();
			}
		}
	}

	// ------------------------------------------------------------------ classic link (different ROMs) through LinkPump

	private final List<Player> players = new ArrayList<>();
	private ScheduledExecutorService sched;
	/** Both players have their cable plugged (pump applied) before either runs a frame: a START sent to a partner that is still booting would be dropped by the relay (as the server does for an unlinked player) and the master would wait for it. */
	private final java.util.concurrent.CountDownLatch bothPlugged = new java.util.concurrent.CountDownLatch(2);

	@AfterEach
	void stopPlayers() throws InterruptedException {
		for (Player p : players) p.stop = true;
		for (Player p : players) p.thread.join(5000);
		if (sched != null) sched.shutdownNow();
	}

	/** One emulator thread driving a LinkPump like EmulatorSession, with the other player reached through a delayed relay. */
	final class Player implements Runnable {
		final int id, base, role;
		final String rom;
		final Thread thread;
		final ConcurrentLinkedQueue<Runnable> cmds = new ConcurrentLinkedQueue<>();
		volatile LinkPump pump;
		volatile boolean stop;
		volatile int frames;
		volatile byte[] snapshot = new byte[0];
		volatile Throwable failure;
		final long latencyMs;

		Player(int id, String rom, int role, int base, long latencyMs) {
			this.id = id; this.rom = rom; this.role = role; this.base = base; this.latencyMs = latencyMs;
			thread = new Thread(this, "gb-player-" + id);
			thread.setDaemon(true);
		}

		void relay(byte[] m) {
			Player o = players.get(1 - id);
			sched.schedule(() -> { if (o.pump != null) o.pump.offer(id, m); }, latencyMs, TimeUnit.MILLISECONDS);
		}

		@Override
		public void run() {
			try (GbaCore c = booted(rom)) {
				configure(c, role, base, 1, 0x81);
				LinkPump p = new LinkPump(this::relay, System::nanoTime, 20_000_000_000L, () -> failure = new IllegalStateException("link timeout"), 0, 0, List.of());
				p.bind(c);
				p.apply(id, 2, List.of("a", "b"));
				pump = p;
				bothPlugged.countDown();
				if (!bothPlugged.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("partner never plugged in");
				BooleanSupplier stopped = () -> stop;
				long next = System.nanoTime();
				while (!stop) {
					Runnable r;
					while ((r = cmds.poll()) != null) r.run();
					p.deliverIncoming();
					if (p.runFrame(stopped, () -> { }) == GbaNative.RUN_ERROR) fail("core error");
					short[] pcm = new short[4096];
					while (c.readAudio(pcm, 2048) > 0) { }
					frames++;
					snapshot = c.saveState();
					next += 16_742_706L;
					long w = next - System.nanoTime();
					if (w > 0) { if (!p.awaitAndDeliver(w)) { /* slept */ } } else if (-w > 100_000_000L) next = System.nanoTime();
				}
			} catch (Throwable t) {
				t.printStackTrace();
				failure = t;
			}
		}
	}

	private void classic(String masterRom, String slaveRom, long latencyMs) throws InterruptedException {
		sched = Executors.newSingleThreadScheduledExecutor();
		players.add(new Player(0, masterRom, 1, 0x10, latencyMs));
		players.add(new Player(1, slaveRom, 2, 0xA0, latencyMs));
		for (Player p : players) p.thread.start();
		long end = System.nanoTime() + 30_000_000_000L;
		while (System.nanoTime() < end && !(peek0(players.get(0)) >= 24 && peek0(players.get(1)) >= 24)) Thread.sleep(10);
		for (Player p : players) assertNull(p.failure, "player " + p.id);
		assertTrue(peek0(players.get(0)) >= 24 && peek0(players.get(1)) >= 24, "transfers " + peek0(players.get(0)) + " / " + peek0(players.get(1)));
		verify(players.get(0).snapshot, players.get(1).snapshot, 24, 0x10, 0xA0);
	}

	private static int peek0(Player p) { return p.snapshot.length == 0 ? 0 : peek(p.snapshot, 0xC111); }

	@Test
	void classicLinkBetweenDifferentRomsAndSystems() throws InterruptedException {
		classic("gblink_dmg.gb", "gblink_dmg_b.gb", 0);
	}

	@Test
	void classicLinkWithNetworkLatency() throws InterruptedException {
		classic("gblink_dmg.gb", "gblink_dmg_b.gb", 8); // 16 ms round trip: every byte waits for it
	}

	@Test
	void classicLinkGameBoyWithGameBoyColor() throws InterruptedException {
		classic("gblink_cgb.gbc", "gblink_dmg.gb", 4);
	}

	@Test
	void serverRulesPutOnlyCompatibleGamesInOneLink() {
		var room = List.of(1, 2, 3);
		Set<Integer> open = ConcurrentHashMap.newKeySet();
		open.addAll(room);
		java.util.Map<Integer, String> sys = java.util.Map.of(1, "gbc", 2, "gba", 3, "gb");
		assertEquals(List.of(1, 3), EffectiveLink.active(room, open::contains, sys::get));
	}
}
