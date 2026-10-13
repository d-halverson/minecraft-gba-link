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
}
