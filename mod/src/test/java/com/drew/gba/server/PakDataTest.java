package com.drew.gba.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PakDataTest {
	private static final String SHA = "a".repeat(40);

	@Test
	void systemDefaultsToGbaAndIsNormalised() {
		PakData old = new PakData(UUID.randomUUID(), SHA, "Emerald", "BPEE");
		assertEquals("gba", old.system());
		assertFalse(old.gameBoy());
		assertEquals("gbc", new PakData(UUID.randomUUID(), SHA, "Crystal", "BYTE", " GBC ").system());
		assertEquals("gb", new PakData(UUID.randomUUID(), SHA, "Tetris", "G123", "gb").system());
		assertEquals("gba", new PakData(UUID.randomUUID(), SHA, "x", "ABCD", null).system());
		assertEquals("gba", new PakData(UUID.randomUUID(), SHA, "x", "ABCD", "n64").system(), "unknown values become gba");
		assertTrue(new PakData(UUID.randomUUID(), SHA, "x", "ABCD", "gb").gameBoy());
		assertTrue(PakData.isGameBoy("gbc"));
		assertFalse(PakData.isGameBoy(""));
	}

	@Test
	void gameBoyGamesValidateLikeGbaOnes() {
		assertTrue(PakData.validSha1(SHA));
		assertEquals("POKEMON GOLD", PakData.sanitizeTitle("POKEMON GOLD"));
		assertEquals("G332", PakData.sanitizeCode("G332"));
		assertEquals("BYTE", PakData.sanitizeCode("BYTE"));
		assertEquals("Game Boy Color", PakData.systemName("gbc"));
		assertEquals("Game Boy", PakData.systemName("gb"));
		assertEquals("Game Boy Advance", PakData.systemName(null));
	}
}
