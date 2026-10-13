package com.drew.gba.client;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.emu.LinkPump;
import com.drew.gba.server.EffectiveLink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs real cores (libgbacore through the mod's own binding) with the link test ROMs, relayed through
 * a fake in-memory server that uses {@link EffectiveLink}. Skipped when the native library is missing.
 * The ROMs are our own homebrew from native/tests/make_link_rom.py (see that file for the EWRAM log layout).
 */
class LinkIntegrationTest {
	private static final int WRAM_OFF = 0x61000 - 0x40000; // EWRAM inside mGBA's raw save state
	private static final int LOG = 0x100;
	private static GbaNative natives;
	private static Path romDir;

	@TempDir static Path tmp;

	private FakeServer server;
	private final List<Player> players = new ArrayList<>();

	@BeforeAll
	static void load() throws IOException {
		String platform = GbaNative.platform();
		String lib = platform.startsWith("macos") ? "libgbacore.dylib" : platform.startsWith("windows") ? "gbacore.dll" : "libgbacore.so";
		Path src = Path.of("..", "dist", "natives", platform, lib).toAbsolutePath().normalize();
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) { // CI: a missing native must fail, not skip
			assertTrue(Files.isRegularFile(src), "native library missing: " + src);
		}
		Assumptions.assumeTrue(Files.isRegularFile(src), "native library not built for " + platform);
		Path dir = tmp.resolve("gbalink").resolve("natives").resolve(platform);
		Files.createDirectories(dir);
		Files.copy(src, dir.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
		natives = GbaNative.get(tmp);
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertNotNull(natives, "native library failed to load: " + GbaNative.loadError());
		Assumptions.assumeTrue(natives != null, "native library failed to load: " + GbaNative.loadError());
		romDir = tmp.resolve("roms");
		Files.createDirectories(romDir);
		for (String n : List.of("n32_master", "n32_slave", "n8_master", "n8_slave")) {
			try (InputStream in = LinkIntegrationTest.class.getResourceAsStream("/roms/link_" + n + ".gba")) {
				Files.copy(in, romDir.resolve("link_" + n + ".gba"));
			}
		}
	}

	@AfterEach
	void cleanup() throws InterruptedException {
		for (Player p : players) p.stop = true;
		for (Player p : players) p.thread.join(5000);
		if (server != null) server.sched.shutdownNow();
	}

	// ------------------------------------------------------------------ fixtures

	/** The server side: cable room + open sessions -> effective link, relay with latency. */
	static final class FakeServer {
		final List<Integer> room = new ArrayList<>();
		final Set<Integer> open = ConcurrentHashMap.newKeySet();
		final Map<Integer, Player> byId = new ConcurrentHashMap<>();
		final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(); // FIFO per equal delay
		final long latencyMs;

		FakeServer(long latencyMs) { this.latencyMs = latencyMs; }

		synchronized void open(int id) { open.add(id); publish(); }
		synchronized void close(int id) { open.remove(id); publish(); }

		void publish() {
			Map<Integer, EffectiveLink.State> st = EffectiveLink.states(room, open::contains);
			List<String> names = EffectiveLink.active(room, open::contains).stream().map(i -> "P" + i).toList();
			for (var e : st.entrySet()) {
				Player p = byId.get(e.getKey());
				EffectiveLink.State s = e.getValue();
				p.cmds.add(() -> p.pump.apply(s.index(), s.count(), s.plugged() ? names : List.of()));
			}
		}

		void relay(int from, byte[] msg) {
			List<Integer> act = EffectiveLink.active(room, open::contains);
			int idx = act.indexOf(from);
			if (idx < 0) return;
			for (int to : act) {
				if (to == from) continue;
				sched.schedule(() -> {
					Player p = byId.get(to);
					if (p.pump != null) p.pump.offer(idx, msg);
				}, latencyMs, TimeUnit.MILLISECONDS);
			}
		}
	}

	/** One emulator thread (cores are thread confined) driving a LinkPump like EmulatorSession does. */
	final class Player implements Runnable {
		final int id, base;
		final String rom;
		final ConcurrentLinkedQueue<Runnable> cmds = new ConcurrentLinkedQueue<>();
		final AtomicInteger timeouts = new AtomicInteger();
		final Thread thread;
		final long timeoutNanos;
		volatile LinkPump pump;
		GbaCore core;
		volatile boolean stop, paused;
		volatile int frames;
		volatile byte[] snapshot = new byte[0];

		Player(int id, String rom, int base, long timeoutMs) {
			this.id = id; this.rom = rom; this.base = base;
			this.timeoutNanos = timeoutMs * 1_000_000L;
			thread = new Thread(this, "test-player-" + id);
			thread.setDaemon(true);
		}

		/** Let the master ROM start transferring (called once the link is up and the partner is armed). */
		void openGate() {
			cmds.add(() -> { patch(core, 0xF4, 1); pump.apply(pump.index(), pump.count(), pump.names()); });
		}

		void drain() {
			Runnable r;
			while ((r = cmds.poll()) != null) r.run();
		}

		/** Runs while the master is blocked: a frame can contain hundreds of transfers, so also snapshot here. */
		private long lastSnap;
		void service() {
			drain();
			long now = System.nanoTime();
			if (now - lastSnap > 50_000_000L) { lastSnap = now; snapshot = core.saveState(); }
		}

		@Override
		public void run() {
			try (GbaCore c = new GbaCore(natives)) {
				core = c;
				assertEquals(0, core.loadRom(romDir.resolve("link_" + rom + ".gba")));
				patch(core, 0xF0, base);
				LinkPump p = new LinkPump(m -> server.relay(id, m), System::nanoTime, timeoutNanos, timeouts::incrementAndGet,
					0, 0, List.of());
				p.bind(core);
				p.apply(0, 0, List.of());
				pump = p;
				BooleanSupplier stopped = () -> stop;
				while (!stop) {
					if (paused) { Thread.sleep(1); continue; }
					drain();
					p.deliverIncoming();
					if (p.runFrame(stopped, this::service) == GbaNative.RUN_ERROR) fail("core error");
					frames++;
					snapshot = core.saveState();
				}
			} catch (Throwable t) {
				t.printStackTrace();
				failure = t;
			}
		}

		volatile Throwable failure;

		/** Write a word into EWRAM through a save-state round trip (the ABI has no memory access). */
		private void patch(GbaCore core, int off, int value) {
			byte[] st = core.saveState();
			assertEquals(0x61000, st.length, "unexpected save state layout");
			ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN).putInt(WRAM_OFF + off, value);
			assertTrue(core.loadState(st));
		}

		long word(int off) {
			byte[] s = snapshot;
			return s.length < 0x61000 ? 0 : ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN).getInt(WRAM_OFF + off) & 0xFFFFFFFFL;
		}
		long count() { return word(0); }
		long sent(int k) { return word(LOG + 16 * k); }
		long received(int k) { return word(LOG + 16 * k + 4); }
	}

	private Player player(int id, String rom, int base, long timeoutMs) {
		Player p = new Player(id, rom, base, timeoutMs);
		players.add(p);
		server.room.add(id);
		server.byId.put(id, p);
		return p;
	}

	private void startAll() throws InterruptedException {
		for (Player p : players) p.thread.start();
		await(() -> players.stream().allMatch(p -> p.pump != null), 10_000, "players booted");
	}

	/** Open the link, let the partners run a few frames armed, then let the masters go. */
	private void linkUp(int... ids) throws InterruptedException {
		for (int id : ids) server.open(id);
		await(() -> players.stream().filter(p -> server.open.contains(p.id)).allMatch(p -> p.pump.count() == ids.length && p.frames >= 10), 10_000, "link up");
		for (Player p : players) if (p.rom.endsWith("master")) p.openGate();
	}

	private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
		long end = System.nanoTime() + ms * 1_000_000L;
		while (System.nanoTime() < end) {
			if (c.getAsBoolean()) return;
			Thread.sleep(2);
		}
		fail("timeout waiting for " + what);
	}

	private void assertNoFailures() {
		for (Player p : players) assertNull(p.failure, "player " + p.id + " threw");
	}

	// ------------------------------------------------------------------ tests

	private void pairTransfers(String m, String s, long mask, long latency) throws InterruptedException {
		server = new FakeServer(latency);
		Player a = player(0, m, 0xA5A50000, 5000), b = player(1, s, 0x5A5A0100, 5000);
		startAll();
		linkUp(0, 1);
		int n = 20;
		await(() -> a.count() >= n && b.count() >= n, 20_000, "transfers (a=" + a.count() + " b=" + b.count() + ")");
		assertNoFailures();
		for (int k = 0; k < n; k++) {
			assertEquals((a.base + k) & mask, a.sent(k), "master sent " + k);
			assertEquals((b.base + k) & mask, b.sent(k), "slave sent " + k);
			assertEquals(b.sent(k), a.received(k), "master received " + k);
			assertEquals(a.sent(k), b.received(k), "slave received " + k);
		}
		assertEquals(0, a.timeouts.get());
	}

	@Test
	void normal32TransfersBothWaysWithLatency() throws InterruptedException {
		pairTransfers("n32_master", "n32_slave", 0xFFFFFFFFL, 8);
	}

	@Test
	void normal8TransfersBothWaysWithLatency() throws InterruptedException {
		pairTransfers("n8_master", "n8_slave", 0xFFL, 3);
	}

	@Test
	void effectiveIndexSkipsClosedRoomMember() throws InterruptedException {
		// room [0,1,2]; 1 is cabled but never opens its GBA, so 2 becomes effective player 1
		server = new FakeServer(2);
		Player a = player(0, "n32_master", 0x11110000, 5000);
		player(1, "n32_slave", 0x22220000, 5000);
		Player c = player(2, "n32_slave", 0x33330100, 5000);
		startAll();
		linkUp(0, 2);
		await(() -> a.count() >= 10 && c.count() >= 10, 20_000, "transfers");
		assertNoFailures();
		assertEquals(1, c.pump.index());
		assertEquals(2, c.pump.count());
		for (int k = 0; k < 10; k++) {
			assertEquals(c.sent(k), a.received(k));
			assertEquals(a.sent(k), c.received(k));
		}
		assertEquals(0, players.get(1).pump.count());
	}

	@Test
	void masterUnblocksWhenPartnerClosesWhileWaiting() throws InterruptedException {
		server = new FakeServer(2);
		Player a = player(0, "n32_master", 0xA5A50000, 60_000), b = player(1, "n32_slave", 0x5A5A0100, 60_000);
		startAll();
		linkUp(0, 1);
		await(() -> a.count() >= 3, 20_000, "initial transfers");
		b.paused = true; // partner hangs mid-session: the master blocks in LINK_WAIT
		await(() -> a.pump.waiting(), 5_000, "master waiting");
		int f = a.frames;
		Thread.sleep(200);
		assertEquals(f, a.frames, "master should be stuck");
		server.close(1); // effective link drops to a single player -> unplugged for the master
		await(() -> a.frames > f + 5, 5_000, "master running again");
		assertFalse(a.pump.waiting());
		assertFalse(a.pump.timedOut(), "unblocked by the unplug, not by the timeout");
		assertEquals(0, a.timeouts.get());
		assertEquals(0, a.pump.count());
		assertNoFailures();
	}

	@Test
	void timeoutUnplugsAndNextLinkStateReplugs() throws InterruptedException {
		server = new FakeServer(2);
		Player a = player(0, "n32_master", 0xA5A50000, 300), b = player(1, "n32_slave", 0x5A5A0100, 300);
		startAll();
		linkUp(0, 1);
		await(() -> a.count() >= 3, 20_000, "initial transfers");
		b.paused = true;
		await(() -> a.pump.timedOut(), 5_000, "master timeout");
		assertEquals(1, a.timeouts.get());
		assertEquals(0, a.pump.count());
		b.paused = false;
		long before = a.count();
		server.close(1); // a session change makes the server resend link_state; re-plug
		server.open(1);
		await(() -> !a.pump.timedOut() && a.pump.count() == 2, 5_000, "replug");
		await(() -> a.count() >= before + 5 && b.count() >= 5, 20_000, "transfers after replug");
		assertNoFailures();
	}
}
