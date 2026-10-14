package com.drew.gba.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveLinkTest {
	private static final List<String> ROOM = List.of("a", "b", "c", "d");

	@Test
	void onlyOpenMembersCountInJoinOrder() {
		Set<String> open = Set.of("a", "c", "d");
		Map<String, EffectiveLink.State> s = EffectiveLink.states(ROOM, open::contains);
		assertEquals(new EffectiveLink.State(0, 3), s.get("a"));
		assertEquals(EffectiveLink.State.UNPLUGGED, s.get("b"));
		assertEquals(new EffectiveLink.State(1, 3), s.get("c"));
		assertEquals(new EffectiveLink.State(2, 3), s.get("d"));
		assertEquals(1, EffectiveLink.indexOf(ROOM, open::contains, "c"));
		assertEquals(-1, EffectiveLink.indexOf(ROOM, open::contains, "b"));
	}

	@Test
	void fewerThanTwoIsUnplugged() {
		for (Set<String> open : List.of(Set.<String>of(), Set.of("b"))) {
			for (EffectiveLink.State st : EffectiveLink.states(ROOM, open::contains).values()) {
				assertFalse(st.plugged());
				assertEquals(EffectiveLink.State.UNPLUGGED, st);
			}
			assertEquals(-1, EffectiveLink.indexOf(ROOM, open::contains, "b"));
		}
		assertTrue(EffectiveLink.active(ROOM, Set.of("a")::contains).isEmpty());
	}

	@Test
	void indicesFollowSessionsOpeningAndClosing() {
		java.util.Set<String> open = new java.util.HashSet<>();
		open.add("c");
		assertFalse(EffectiveLink.states(ROOM, open::contains).get("c").plugged());
		open.add("d");
		assertEquals(new EffectiveLink.State(0, 2), EffectiveLink.states(ROOM, open::contains).get("c"));
		open.add("a"); // joins ahead of c in room order, so c shifts to index 1
		assertEquals(new EffectiveLink.State(1, 3), EffectiveLink.states(ROOM, open::contains).get("c"));
		open.remove("a");
		open.remove("d");
		assertFalse(EffectiveLink.states(ROOM, open::contains).get("c").plugged());
	}

	@Test
	void worksWithRoomsChangingMembers() {
		LinkRooms<String> rooms = new LinkRooms<>();
		List<LinkRooms.Update<String>> up = new java.util.ArrayList<>();
		rooms.link("a", "b", up);
		rooms.link("a", "c", up);
		Set<String> open = Set.of("b", "c");
		assertEquals(new EffectiveLink.State(0, 2), EffectiveLink.states(rooms.room("a"), open::contains).get("b"));
		rooms.remove("b", up);
		assertEquals(List.of("a", "c"), rooms.room("a"));
		assertFalse(EffectiveLink.states(rooms.room("a"), open::contains).get("c").plugged());
	}

	@Test
	void netplayNeedsTwoPlayersTheSameRomAndCapableClients() {
		java.util.Map<String, String> sha = java.util.Map.of("a", "AA11", "b", "aa11", "c", "bb22", "d", "");
		assertTrue(EffectiveLink.netplay(List.of("a", "b"), sha::get, k -> true), "same ROM (case-insensitive) -> netplay");
		assertFalse(EffectiveLink.netplay(List.of("a", "c"), sha::get, k -> true), "different ROM -> classic link");
		assertFalse(EffectiveLink.netplay(List.of("a", "b", "c"), sha::get, k -> true), "one different pak in three -> classic link");
		assertFalse(EffectiveLink.netplay(List.of("a"), sha::get, k -> true), "one player is not a link");
		assertFalse(EffectiveLink.netplay(List.of("a", "b"), sha::get, k -> !k.equals("b")), "a client without the capability -> classic");
		assertFalse(EffectiveLink.netplay(List.of("a", "d"), sha::get, k -> true), "unknown ROM -> classic");
		assertFalse(EffectiveLink.netplay(List.of("a", "zz"), sha::get, k -> true), "no pak data -> classic");
		var st = EffectiveLink.states(ROOM, java.util.Set.of("c", "d")::contains, true);
		assertTrue(st.get("c").netplay());
		assertFalse(st.get("a").netplay());
		assertNotEquals(EffectiveLink.states(ROOM, java.util.Set.of("c", "d")::contains, false).get("c"), st.get("c"));
	}
}
