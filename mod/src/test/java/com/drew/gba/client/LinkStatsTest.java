package com.drew.gba.client;

import com.drew.gba.client.emu.LinkLog;
import com.drew.gba.client.emu.LinkStats;
import com.drew.gba.server.RelayCounters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LinkStatsTest {
	private static final long MS = 1_000_000L;

	@Test
	void masterWindowComputesRatesAndWaits() {
		LinkStats st = new LinkStats(0);
		long t = 0;
		for (int i = 0; i < 60; i++) { // 60 frames/s, one exchange per frame, 10 ms stall (20 ms once)
			long w = i == 30 ? 20 * MS : 10 * MS;
			st.onOutgoing(new byte[] {1, 0}, t);
			st.onOutgoing(new byte[] {3, 0}, t + w);
			st.recordBlocked(w);
			st.recordFrame();
			t += 1_000_000_000L / 60;
		}
		st.tick(1_000_000_000L);
		LinkStats.Snapshot s = st.latest();
		assertTrue(s.master());
		assertEquals(60, s.exchanges());
		assertEquals(10.17, s.avgWaitMs(), 0.05);
		assertEquals(20, s.maxWaitMs(), 0.001);
		assertEquals(10, s.minWaitMs(), 0.001);
		assertEquals(60, s.fps(), 0.5);
		assertEquals(61.0, s.waitPercent(), 1.5);
		assertTrue(s.format().contains("rtt~10 ms"), s.format());
	}

	@Test
	void childCountsOnlyDoneMessages() {
		LinkStats st = new LinkStats(0);
		st.onIncoming(new byte[] {1, 0});
		st.onIncoming(new byte[] {3, 0});
		st.onIncoming(new byte[] {4, 0});
		st.onIncoming(new byte[0]);
		st.onOutgoing(new byte[] {2, 1}, 5);
		st.tick(1_000_000_000L);
		LinkStats.Snapshot s = st.latest();
		assertFalse(s.master());
		assertEquals(1, s.exchanges());
		assertTrue(s.format().startsWith("Link: 1 xchg/s"));
		assertNull(st.rollLog(1_000_000_000L, 5_000_000_000L));
		assertNotNull(st.rollLog(5_000_000_000L, 5_000_000_000L));
	}

	@Test
	void clearDropsStaleNumbers() {
		LinkStats st = new LinkStats(0);
		st.recordFrame();
		st.tick(1_000_000_000L);
		assertNotEquals("", st.latest().format());
		st.clear(2_000_000_000L);
		assertEquals("", st.latest().format());
	}

	@Test
	void logRotatesAtLimit(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("gbalink").resolve("link.log");
		LinkLog log = new LinkLog(f, 200);
		for (int i = 0; i < 20; i++) log.append(LinkLog.line(1_700_000_000_000L + i, "Pokemon", "master", 2, "Link: 59 xchg/s"));
		assertTrue(Files.size(f) <= 200);
		assertTrue(Files.isRegularFile(dir.resolve("gbalink").resolve("link.log.1")));
		String l = Files.readAllLines(f).get(0);
		assertTrue(l.contains("| Pokemon | master | players=2 |"), l);
	}

	@Test
	void relayCountersAreRateLimitedAndSilentWhenIdle() {
		RelayCounters rc = new RelayCounters(5000);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		List<UUID> room = List.of(a, b);
		assertNull(rc.poll(room, 0));
		for (int i = 0; i < 100; i++) rc.count(i % 2 == 0 ? a : b);
		assertNull(rc.poll(room, 4_000)); // too soon
		RelayCounters.Rate r = rc.poll(room, 5_000);
		assertNotNull(r);
		assertEquals(100, r.messages());
		assertEquals(20.0, r.perSecond(), 0.001);
		assertNull(rc.poll(room, 10_000)); // nothing relayed: no line
		rc.count(a);
		assertNull(rc.poll(room, 12_000));
		assertEquals(1, rc.poll(room, 15_000).messages());
	}
}
