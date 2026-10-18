package com.drew.gba.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinkRulesTest {
	@Test
	void familiesAndCompatibility() {
		assertEquals(LinkRules.Family.GBA, LinkRules.family(null));
		assertEquals(LinkRules.Family.GBA, LinkRules.family("gba"));
		assertEquals(LinkRules.Family.GBA, LinkRules.family("junk"));
		assertEquals(LinkRules.Family.GAME_BOY, LinkRules.family("gb"));
		assertEquals(LinkRules.Family.GAME_BOY, LinkRules.family("GBC"));
		assertTrue(LinkRules.compatible("gb", "gbc"));
		assertTrue(LinkRules.compatible("gbc", "gbc"));
		assertTrue(LinkRules.compatible("gb", "gb"));
		assertTrue(LinkRules.compatible("gba", null));
		assertFalse(LinkRules.compatible("gba", "gb"));
		assertFalse(LinkRules.compatible("gbc", "gba"));
		assertEquals(4, LinkRules.maxPlayers(LinkRules.Family.GBA));
		assertEquals(2, LinkRules.maxPlayers(LinkRules.Family.GAME_BOY));
	}

	private static List<String> select(Map<String, String> sys, String... order) {
		return LinkRules.select(List.of(order), sys::get);
	}

	@Test
	void selectionPicksTheBiggestFamilyAndBreaksTiesByRoomOrder() {
		Map<String, String> m = Map.of("a", "gba", "b", "gb", "c", "gbc", "d", "gba", "e", "gba");
		assertEquals(List.of("a", "d", "e"), select(m, "a", "b", "c", "d", "e"), "three GBA beat two Game Boy");
		assertEquals(List.of("b", "c"), select(m, "b", "c", "a"), "one GBA alone cannot link");
		assertEquals(List.of("a", "d"), select(m, "a", "b", "d", "c"), "tie 2:2 -> the first family in the room (GBA)");
		assertEquals(List.of("b", "c"), select(m, "b", "a", "c", "d"), "tie 2:2 -> Game Boy is first here");
		assertEquals(List.of(), select(m, "a", "b"), "one of each cannot link");
		assertEquals(List.of(), select(m, "a"));
		assertEquals(List.of(), select(m));
		assertEquals(List.of("b", "c"), select(Map.of("b", "gb", "c", "gbc", "x", "gb"), "b", "c", "x"), "a Game Boy cable joins only two");
	}

	@Test
	void exclusionsExplainWhyAMemberIsLeftOut() {
		Map<String, String> m = Map.of("a", "gba", "b", "gb", "c", "gb", "d", "gb");
		List<String> cand = List.of("a", "b", "c", "d");
		List<String> sel = LinkRules.select(cand, m::get);
		assertEquals(List.of("b", "c"), sel);
		assertEquals(LinkRules.Exclusion.NONE, LinkRules.exclusion("b", cand, sel, m::get));
		assertEquals(LinkRules.Exclusion.TOO_MANY, LinkRules.exclusion("d", cand, sel, m::get));
		assertEquals(LinkRules.Exclusion.OTHER_SYSTEM, LinkRules.exclusion("a", cand, sel, m::get));
		assertEquals(LinkRules.Exclusion.NONE, LinkRules.exclusion("a", List.of("a"), List.of(), m::get), "alone: nothing to explain");
		assertEquals(LinkRules.Exclusion.OTHER_SYSTEM, LinkRules.exclusion("a", List.of("a", "b"), List.of(), m::get));
		assertEquals(LinkRules.Exclusion.NONE, LinkRules.exclusion("zz", cand, sel, m::get));
	}

	@Test
	void messagesNameTheOthers() {
		String m = LinkRules.mismatchMessage("gb", List.of("Bob"), List.of("gba"));
		assertEquals("Can't link: Bob plays a Game Boy Advance game, you play a Game Boy / Color game", m);
		assertEquals("Can't link: Ann, Bob play a Game Boy / Color game, you play a Game Boy Advance game",
			LinkRules.mismatchMessage("gba", List.of("Ann", "Bob"), List.of("gbc", "gb")));
		assertNull(LinkRules.mismatchMessage("gb", List.of("Bob"), List.of("gbc")));
		assertNull(LinkRules.mismatchMessage("gba", List.of("Bob", "Cy"), List.of("", "gba")), "members without a game open say nothing");
		assertTrue(LinkRules.tooManyMessage(List.of("A", "B")).contains("A and B"));
	}
}
