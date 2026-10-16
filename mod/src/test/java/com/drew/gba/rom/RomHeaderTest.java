package com.drew.gba.rom;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RomHeaderTest {
	static byte[] rom(String name) throws IOException {
		try (InputStream in = RomHeaderTest.class.getResourceAsStream("/roms/" + name)) {
			return in.readAllBytes();
		}
	}

	private static RomHeader.Result parse(String file, byte[] data) {
		return RomHeader.parse(file, data.length, Arrays.copyOf(data, Math.min(data.length, RomHeader.HEAD_BYTES)));
	}

	/** Recompute the Game Boy header checksum after a test edited the header. */
	private static void fixChecksum(byte[] r) {
		int x = 0;
		for (int i = 0x134; i <= 0x14C; i++) x = (x - (r[i] & 0xFF) - 1) & 0xFF;
		r[0x14D] = (byte) x;
	}

	@Test
	void monochromeGameBoy() throws IOException {
		RomHeader.Result r = parse("gb_dmg.gb", rom("gb_dmg.gb"));
		assertTrue(r.ok(), r.error());
		assertEquals(new RomHeader("gb", "GBTESTDMG", "G332"), r.header());
	}

	@Test
	void gameBoyColorWithManufacturerCode() throws IOException {
		RomHeader.Result r = parse("gb_cgb.gbc", rom("gb_cgb.gbc"));
		assertTrue(r.ok(), r.error());
		assertEquals(new RomHeader("gbc", "GBTESTCGB", "GBCT"), r.header());
	}

	@Test
	void systemComesFromTheCgbFlagNotTheExtension() throws IOException {
		byte[] color = rom("gb_cgb.gbc");
		assertEquals("gbc", parse("renamed.gb", color).header().system());
		byte[] mono = rom("gb_dmg.gb");
		assertEquals("gb", parse("renamed.gbc", mono).header().system());
		byte[] hybrid = mono.clone(); // 0x80 = works on both: still a Game Boy Color game
		hybrid[0x143] = (byte) 0x80;
		fixChecksum(hybrid);
		assertEquals("gbc", parse("hybrid.gb", hybrid).header().system());
		assertEquals("gb", parse("sgb.gb", rom("gb_sgb.gb")).header().system()); // SGB flag: still monochrome
	}

	@Test
	void gbaHeader() throws IOException {
		RomHeader.Result r = parse("counter.gba", rom("counter.gba"));
		assertTrue(r.ok(), r.error());
		assertEquals(new RomHeader("gba", "COUNTER", "CNTR"), r.header());
	}

	@Test
	void gameBoyHeaderValidation() throws IOException {
		byte[] bad = rom("gb_dmg.gb");
		bad[0x14D] ^= 0x55;
		assertTrue(parse("x.gb", bad).error().contains("checksum"));
		byte[] nologo = rom("gb_dmg.gb");
		nologo[0x104] = 0;
		assertTrue(parse("x.gb", nologo).error().contains("logo"));
		byte[] tiny = Arrays.copyOf(rom("gb_dmg.gb"), 0x120);
		assertTrue(parse("x.gb", tiny).error().contains("small"));
		assertTrue(parse("x.gb", new byte[0]).error().contains("small"));
	}

	@Test
	void sizeLimits() throws IOException {
		byte[] gb = rom("gb_dmg.gb");
		byte[] head = Arrays.copyOf(gb, RomHeader.HEAD_BYTES);
		assertTrue(RomHeader.parse("x.gb", RomHeader.MAX_GB_BYTES, head).ok());
		String why = RomHeader.parse("x.gbc", RomHeader.MAX_GB_BYTES + 1, head).error();
		assertTrue(why.contains("8 MiB"), why);
		byte[] gba = rom("counter.gba");
		byte[] gbaHead = Arrays.copyOf(gba, RomHeader.HEAD_BYTES);
		assertTrue(RomHeader.parse("x.gba", RomHeader.MAX_GBA_BYTES, gbaHead).ok());
		why = RomHeader.parse("x.gba", RomHeader.MAX_GBA_BYTES + 1, gbaHead).error();
		assertTrue(why.contains("32 MiB"), why);
		// a 9 MiB file is fine for a GBA game, too big for a Game Boy game
		assertTrue(RomHeader.parse("x.gba", 9L << 20, gbaHead).ok());
		assertFalse(RomHeader.parse("x.gb", 9L << 20, head).ok());
	}

	@Test
	void gbaHeaderValidation() throws IOException {
		byte[] r = rom("counter.gba");
		r[0xB2] = 0;
		assertTrue(parse("x.gba", r).error().contains("0x96"));
		assertTrue(parse("x.gba", new byte[0x80]).error().contains("small"));
		// a stale complement checksum is tolerated (patched games, homebrew)
		byte[] stale = rom("counter.gba");
		stale[0xBD] ^= 0x42;
		assertTrue(parse("x.gba", stale).ok());
	}

	@Test
	void extensionsAndNames() {
		assertEquals("gba", RomHeader.systemForFileName("A.GBA"));
		assertEquals("gb", RomHeader.systemForFileName("a.gb"));
		assertEquals("gb", RomHeader.systemForFileName("a.GBC"));
		assertNull(RomHeader.systemForFileName("a.sav"));
		assertNull(RomHeader.systemForFileName("a.zip"));
		assertFalse(RomHeader.parse("a.zip", 1000, new byte[RomHeader.HEAD_BYTES]).ok());
		assertEquals("GBC", RomHeader.tag("gbc"));
		assertEquals("GBA", RomHeader.tag(null));
		assertEquals("Game Boy Color", RomHeader.systemName("gbc"));
		assertEquals("Game Boy Advance", RomHeader.systemName(null));
	}

	@Test
	void codeFallsBackToChecksumDigitsWhenThereIsNoManufacturerCode() throws IOException {
		byte[] r = rom("gb_dmg.gb");
		// printable but lowercase / non-alphanumeric bytes are not a manufacturer code
		System.arraycopy("ab c".getBytes(), 0, r, 0x13F, 4);
		fixChecksum(r);
		String code = parse("x.gb", r).header().code();
		assertTrue(code.matches("G[0-9A-F]{3}"), code);
		assertEquals(String.format("G%02X%X", r[0x14D] & 0xFF, r[0x14F] & 15), code);
	}

	@Test
	void truncatedDumpIsAcceptedWithAWarning() throws IOException {
		byte[] r = rom("gb_dmg.gb"); // 32 KiB file
		assertEquals("", parse("ok.gb", r).warning());
		r[0x148] = 6; // header claims 2 MiB, like a cut-off Pokemon Crystal dump
		fixChecksum(r);
		RomHeader.Result res = parse("crystal.gbc", r);
		assertTrue(res.ok(), res.error());
		assertTrue(res.warning().contains("incomplete") && res.warning().contains("32 KB") && res.warning().contains("2048 KB"), res.warning());
		r[0x148] = 0x52; // odd size codes are not checked
		fixChecksum(r);
		assertEquals("", parse("x.gb", r).warning());
	}
}
