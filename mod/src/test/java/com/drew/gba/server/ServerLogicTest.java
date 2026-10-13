package com.drew.gba.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerLogicTest {
	@Test
	void saveAssemblyInOrder() {
		SaveAssembler a = new SaveAssembler();
		assertNull(a.accept(0, 6, new byte[] {1, 2, 3}));
		byte[] done = a.accept(3, 6, new byte[] {4, 5, 6});
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, done);
		// reusable afterwards
		assertArrayEquals(new byte[] {9}, a.accept(0, 1, new byte[] {9}));
	}

	@Test
	void saveAssemblyRejectsBadChunks() {
		SaveAssembler a = new SaveAssembler();
		assertThrows(IllegalArgumentException.class, () -> a.accept(0, SaveAssembler.MAX_TOTAL + 1, new byte[1]));
		assertNull(a.accept(0, 10, new byte[5]));
		assertThrows(IllegalArgumentException.class, () -> a.accept(7, 10, new byte[3])); // gap
		assertThrows(IllegalArgumentException.class, () -> a.accept(5, 10, new byte[5])); // upload was discarded
		assertNull(a.accept(0, 10, new byte[5]));
		assertThrows(IllegalArgumentException.class, () -> a.accept(5, 10, new byte[6])); // overflow
		assertThrows(IllegalArgumentException.class, () -> a.accept(0, 100000, new byte[SaveAssembler.MAX_CHUNK + 1]));
	}

	@Test
	void roomsLinkIndexAndReindex() {
		LinkRooms<String> r = new LinkRooms<>();
		List<LinkRooms.Update<String>> out = new ArrayList<>();
		assertEquals(LinkRooms.LinkResult.SELF, r.link("a", "a", out));
		assertEquals(LinkRooms.LinkResult.LINKED, r.link("a", "b", out));
		assertEquals(LinkRooms.LinkResult.ALREADY_LINKED, r.link("b", "a", out));
		assertEquals(LinkRooms.LinkResult.LINKED, r.link("a", "c", out)); // c joins a's room
		assertEquals(List.of("a", "b", "c"), r.room("c"));
		assertEquals(0, r.indexOf("a"));
		assertEquals(2, r.indexOf("c"));
		assertEquals(LinkRooms.LinkResult.LINKED, r.link("d", "a", out)); // d joins existing room of target
		assertEquals(LinkRooms.LinkResult.ROOM_FULL, r.link("a", "e", out));
		assertEquals(LinkRooms.LinkResult.LINKED, r.link("x", "y", out));
		assertEquals(LinkRooms.LinkResult.OTHER_ROOM, r.link("a", "x", out));

		out.clear();
		r.remove("a", out); // master leaves: b becomes master
		assertEquals(1, out.size());
		assertEquals(List.of("b", "c", "d"), out.get(0).room());
		assertEquals(List.of("a"), out.get(0).released());
		assertEquals(0, r.indexOf("b"));
		assertFalse(r.isLinked("a"));

		out.clear();
		r.remove("b", out);
		r.remove("c", out); // only d left -> room dissolves
		assertEquals(List.of(), out.get(1).room());
		assertTrue(out.get(1).released().containsAll(List.of("c", "d")));
		assertFalse(r.isLinked("d"));
	}

	@Test
	void pakDataSanitizing() {
		assertTrue(PakData.validSha1("0123456789abcdef0123456789abcdef01234567"));
		assertTrue(PakData.validSha1("0123456789ABCDEF0123456789ABCDEF01234567"));
		assertFalse(PakData.validSha1("xyz"));
		assertFalse(PakData.validSha1("0123456789abcdef0123456789abcdef0123456"));
		assertEquals("Pokemon Emerald", PakData.sanitizeTitle("  Pokemon\n\u00a7Emerald  "));
		assertEquals(32, PakData.sanitizeTitle("x".repeat(100)).length());
		assertEquals("BPEE", PakData.sanitizeCode("B-PEE!!!"));
	}

	@Test
	void tokenBucketLimits() {
		TokenBucket b = new TokenBucket(3, 1, 0);
		long t = 0;
		assertTrue(b.take(1, t));
		assertTrue(b.take(1, t));
		assertTrue(b.take(1, t));
		assertFalse(b.take(1, t));
		assertTrue(b.take(1, t + 1_000_000_000L));
	}
}
