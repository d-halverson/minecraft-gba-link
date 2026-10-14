package com.drew.gba.client.netplay;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NetplayUnitTest {
	@Test
	void snapshotRoundTripsAndRejectsGarbage() {
		byte[] st = new byte[400_000];
		for (int i = 0; i < st.length; i += 7) st[i] = (byte) i;
		NetplaySnapshot s = new NetplaySnapshot(1234, 5, new int[] {2}, new byte[][] {st}, new byte[][] {new byte[] {1, 2, 3}});
		byte[] blob = s.encode();
		assertTrue(blob.length < st.length / 2);
		NetplaySnapshot d = NetplaySnapshot.decode(blob);
		assertNotNull(d);
		assertEquals(1234, d.frame());
		assertEquals(5, d.delay());
		assertArrayEquals(new int[] {2}, d.players());
		assertArrayEquals(st, d.states()[0]);
		assertArrayEquals(new byte[] {1, 2, 3}, d.saves()[0]);
		assertNull(NetplaySnapshot.decode(new byte[] {1, 2, 3}));
		assertNull(NetplaySnapshot.decode(Arrays.copyOf(blob, blob.length / 2)));
	}

	@Test
	void delayFollowsTheMeasuredRoundTrip() {
		long sec = 1_000_000_000L;
		DelayModel m = new DelayModel(2, 3, 8, 0);
		assertEquals(2, m.framesFor(20));
		assertEquals(2, m.framesFor(50));
		assertEquals(3, m.framesFor(60));
		assertEquals(4, m.framesFor(100));
		assertEquals(10, m.framesFor(900));
		assertEquals(3, m.proposal());
		// measured RTT of 100 ms raises the proposal right away
		for (int i = 0; i < 20; i++) m.addRtt(1, 100, 100_000_000L * i / 10);
		m.update(2 * sec);
		assertEquals(4, m.proposal());
		assertEquals(100, m.pingMs(), 1);
		// a stall raises it by one immediately (and not again within a second)
		m.onStall(3 * sec, 4);
		assertEquals(5, m.proposal());
		m.onStall(3 * sec + 100_000_000L, 5);
		assertEquals(5, m.proposal());
		// ...and it comes down one step after 10 s without stalls, but never below what the measurement needs
		for (int i = 0; i < 30; i++) m.addRtt(1, 100, 13 * sec + i * 1000);
		m.update(12 * sec);
		assertEquals(5, m.proposal());
		m.update(14 * sec);
		assertEquals(4, m.proposal());
		m.update(30 * sec);
		assertEquals(4, m.proposal());
	}
}
