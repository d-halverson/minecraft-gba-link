package com.drew.gba.crt;

import com.drew.gba.server.TokenBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CrtLogicTest {
	private static List<CrtViewers.Candidate<String>> cands(Object... nd) {
		List<CrtViewers.Candidate<String>> l = new ArrayList<>();
		for (int i = 0; i < nd.length; i += 2) l.add(new CrtViewers.Candidate<>((String) nd[i], ((Number) nd[i + 1]).doubleValue()));
		return l;
	}

	@Test
	void viewersNearestFirstCappedAndLimited() {
		var sel = CrtViewers.select(cands("far", 40, "near", 3, "mid", 10, "edge", 48, "out", 49, "second", 6), 3, 48);
		assertEquals(List.of("near", "second", "mid"), sel.viewers());
		assertEquals(List.of("far", "edge"), sel.limited()); // "out" is beyond the view distance: not even a limit overlay
	}

	@Test
	void tenViewerCapWithTwelvePlayers() {
		List<CrtViewers.Candidate<String>> l = new ArrayList<>();
		for (int i = 0; i < 12; i++) l.add(new CrtViewers.Candidate<>("p" + i, 5 + i));
		var sel = CrtViewers.select(l, 10, 48);
		assertEquals(10, sel.viewers().size());
		assertEquals(List.of("p10", "p11"), sel.limited());
		assertEquals("p0", sel.viewers().get(0));
	}

	@Test
	void zeroViewersMeansEveryoneLimited() {
		var sel = CrtViewers.select(cands("a", 1, "b", 2), 0, 48);
		assertTrue(sel.viewers().isEmpty());
		assertEquals(2, sel.limited().size());
	}

	@Test
	void splitAndReassembleInOrderAndShuffled() {
		byte[] data = new byte[75_000];
		new Random(1).nextBytes(data);
		List<byte[]> parts = StreamSplit.split(data, 30000);
		assertEquals(3, parts.size());
		for (byte[] p : parts) assertTrue(p.length <= 30000);
		var asm = new StreamSplit.Reassembler();
		byte[] out = null;
		for (int i = 0; i < parts.size(); i++) out = asm.accept(7, i, parts.size(), parts.get(i));
		assertArrayEquals(data, out);
		// out of order
		asm = new StreamSplit.Reassembler();
		assertNull(asm.accept(8, 2, 3, parts.get(2)));
		assertNull(asm.accept(8, 0, 3, parts.get(0)));
		assertArrayEquals(data, asm.accept(8, 1, 3, parts.get(1)));
	}

	@Test
	void singlePacketsPassThroughAndEmptyStillOnePart() {
		var asm = new StreamSplit.Reassembler();
		byte[] small = {1, 2, 3};
		assertEquals(1, StreamSplit.split(small, 30000).size());
		assertSame(small, asm.accept(1, 0, 1, small));
		assertEquals(1, StreamSplit.split(new byte[0], 30000).size());
		assertEquals(2, StreamSplit.split(new byte[30001], 30000).size());
	}

	@Test
	void incompleteFrameIsAbandonedWhenANewOneStarts() {
		var asm = new StreamSplit.Reassembler();
		assertNull(asm.accept(1, 0, 3, new byte[10]));
		assertNull(asm.accept(2, 0, 2, new byte[10])); // frame 1 lost a part
		assertEquals(1, asm.dropped);
		assertEquals(20, asm.accept(2, 1, 2, new byte[10]).length);
		assertNull(asm.accept(3, 9, 3, new byte[1])); // bad part index ignored
	}

	@Test
	void rateLimiterAllowsAboutOneMegabytePerSecond() {
		long t = 0;
		TokenBucket b = new TokenBucket(256 * 1024, 1024 * 1024, t);
		int sent = 0, dropped = 0;
		// a streamer pushing 3 MB/s in 30 KB packets for 2 simulated seconds
		for (int i = 0; i < 200; i++) {
			t += 10_000_000L; // 10 ms
			if (b.take(30_000, t)) sent += 30_000; else dropped++;
		}
		assertTrue(sent < 2 * 1024 * 1024 + 256 * 1024 + 30_000, "sent " + sent);
		assertTrue(sent > 1_800_000, "sent " + sent);
		assertTrue(dropped > 50);
		// a normal 300 KB/s stream is never limited
		b = new TokenBucket(256 * 1024, 1024 * 1024, 0);
		t = 0;
		for (int i = 0; i < 300; i++) {
			t += 33_000_000L;
			assertTrue(b.take(10_000, t));
		}
	}

	@Test
	void configIsCreatedWithDefaultsAndParsed(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("config").resolve("gbalink.properties");
		CrtConfig c = CrtConfig.load(f);
		assertEquals(10, c.maxViewers());
		assertEquals(48, c.viewDistance());
		assertTrue(Files.isRegularFile(f));
		Files.writeString(f, "crt_max_viewers=1\ncrt_view_distance=24\n");
		c = CrtConfig.load(f);
		assertEquals(1, c.maxViewers());
		assertEquals(24, c.viewDistance());
		Files.writeString(f, "crt_max_viewers=abc\n");
		assertEquals(10, CrtConfig.load(f).maxViewers());
	}

	@Test
	void screenIsOnTheFrontOfTheTvForEveryYaw() {
		// front direction of a TV with yaw Y is (-sin Y, cos Y); the screen centre must be on that side of the TV's middle
		for (float yaw : new float[] {0, 90, 180, 270}) {
			double[] mid = CrtGeometry.worldPoint(0, 0, 0, yaw, 8, 8, 8);
			double[] scr = CrtGeometry.screenCenter(0, 0, 0, yaw);
			double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
			double dot = (scr[0] - mid[0]) * fx + (scr[2] - mid[2]) * fz;
			assertTrue(dot > 0.3, "yaw " + yaw + " dot " + dot);
		}
		// a player looking south (yaw 0) gets a TV facing north (front = -z => yaw 180)
		assertEquals(180f, CrtGeometry.yawFacingPlayer(0f));
		assertEquals(0f, CrtGeometry.yawFacingPlayer(180f));
		assertEquals(270f, CrtGeometry.yawFacingPlayer(88f)); // player faces west-ish, TV faces east (front = +x)
		double[][] c = CrtGeometry.screenCorners(0, 0, 0, 180f);
		assertEquals(0, c[0][2] - c[1][2], 1e-9);          // a flat vertical quad
		assertTrue(c[0][1] > c[1][1]);                      // top above bottom
		double w = Math.hypot(c[0][0] - c[3][0], c[0][2] - c[3][2]), h = c[0][1] - c[1][1];
		assertEquals(1.5, w / h, 0.01);                     // the GBA picture is 3:2
	}
}
