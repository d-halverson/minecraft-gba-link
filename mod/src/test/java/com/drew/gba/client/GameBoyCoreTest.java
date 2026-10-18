package com.drew.gba.client;

import com.drew.gba.client.emu.FrameBuffer;
import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.rom.RomHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Game Boy / Game Boy Color through the mod's own binding (GbaNative / GbaCore / FrameBuffer) with the homebrew ROMs from
 * native/tests/make_gb_rom.py. Skipped when the native library is missing.
 */
class GameBoyCoreTest {
	private static GbaNative natives;
	private static Path roms;
	@TempDir static Path tmp;

	@BeforeAll
	static void load() throws IOException {
		String platform = GbaNative.platform();
		String lib = platform.startsWith("macos") ? "libgbacore.dylib" : platform.startsWith("windows") ? "gbacore.dll" : "libgbacore.so";
		Path src = Path.of("..", "dist", "natives", platform, lib).toAbsolutePath().normalize();
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertTrue(Files.isRegularFile(src), "native library missing: " + src);
		Assumptions.assumeTrue(Files.isRegularFile(src), "native library not built for " + platform);
		Path dir = tmp.resolve("gbalink").resolve("natives").resolve(platform);
		Files.createDirectories(dir);
		Files.copy(src, dir.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
		natives = GbaNative.get(tmp);
		if (System.getenv("GBALINK_REQUIRE_NATIVE") != null) assertNotNull(natives, "native library failed to load: " + GbaNative.loadError());
		Assumptions.assumeTrue(natives != null, "native library failed to load: " + GbaNative.loadError());
		roms = tmp.resolve("roms");
		Files.createDirectories(roms);
		for (String n : new String[] {"gb_dmg.gb", "gb_cgb.gbc", "gb_sgb.gb", "counter.gba"}) {
			try (InputStream in = GameBoyCoreTest.class.getResourceAsStream("/roms/" + n)) {
				Files.copy(in, roms.resolve(n));
			}
		}
	}

	@AfterAll
	static void done() {
		natives = null;
	}

	private static GbaCore core(String rom) {
		GbaCore c = new GbaCore(natives);
		assertEquals(0, c.loadRom(roms.resolve(rom)), rom);
		return c;
	}

	private static void run(GbaCore c, int frames, int keys) {
		c.setKeys(keys);
		short[] pcm = new short[4096];
		for (int i = 0; i < frames; i++) {
			assertEquals(GbaNative.RUN_FRAME_DONE, c.runFrame());
			while (c.readAudio(pcm, 2048) > 0) { }
		}
	}

	@Test
	void libraryReportsGameBoySupport() {
		assertTrue(natives.supportsGameBoy());
		assertEquals(5, natives.apiVersion());
	}

	@Test
	void platformSizeAndRomInfo() {
		try (GbaCore c = core("gb_dmg.gb")) {
			assertTrue(c.isGameBoy());
			assertEquals(GbaNative.GB_WIDTH, c.frameWidth());
			assertEquals(GbaNative.GB_HEIGHT, c.frameHeight());
			assertArrayEquals(new String[] {"GBTESTDMG", "G332"}, c.romInfo());
		}
		try (GbaCore c = core("gb_cgb.gbc")) {
			assertArrayEquals(new String[] {"GBTESTCGB", "GBCT"}, c.romInfo());
		}
		try (GbaCore c = core("counter.gba")) {
			assertFalse(c.isGameBoy());
			assertEquals(GbaNative.WIDTH, c.frameWidth());
			assertEquals(GbaNative.HEIGHT, c.frameHeight());
		}
	}

	@Test
	void javaHeaderParserAgreesWithTheCore() throws IOException {
		for (String n : new String[] {"gb_dmg.gb", "gb_cgb.gbc", "gb_sgb.gb"}) {
			byte[] data = Files.readAllBytes(roms.resolve(n));
			RomHeader h = RomHeader.parse(n, data.length, Arrays.copyOf(data, RomHeader.HEAD_BYTES)).header();
			try (GbaCore c = core(n)) {
				assertArrayEquals(new String[] {h.title(), h.code()}, c.romInfo(), n);
			}
		}
	}

	@Test
	void frameBufferKeepsTheNativeSizeAndLetterboxes() {
		try (GbaCore c = core("gb_dmg.gb")) {
			run(c, 90, 0);
			FrameBuffer fb = new FrameBuffer();
			fb.publish(c);
			assertEquals(160, fb.width());
			assertEquals(144, fb.height());
			byte[] raw = new byte[GbaNative.WIDTH * GbaNative.HEIGHT * 4];
			fb.copyTo(raw);
			int sum = 0;
			for (int i = 0; i < 160 * 144 * 4; i++) sum += raw[i] & 0xFF;
			assertTrue(sum > 100000, "picture is not blank");
			byte[] canvas = new byte[GbaNative.WIDTH * GbaNative.HEIGHT * 4];
			fb.copyLetterboxed(canvas);
			// border: opaque black; the picture sits 1:1 at (40, 8)
			assertEquals(0, canvas[0]);
			assertEquals((byte) 255, canvas[3]);
			assertEquals((byte) 255, canvas[(159 * 240 + 239) * 4 + 3]);
			for (int y = 0; y < 144; y += 11) {
				for (int x = 0; x < 160; x += 7) {
					int s = (y * 160 + x) * 4, d = ((y + 8) * 240 + x + 40) * 4;
					assertEquals(raw[s], canvas[d]);
					assertEquals(raw[s + 1], canvas[d + 1]);
					assertEquals(raw[s + 2], canvas[d + 2]);
				}
			}
			assertEquals(0, canvas[(4 * 240 + 20) * 4 + 1], "side border is black");
		}
		try (GbaCore c = core("counter.gba")) { // a GBA picture is passed through unchanged
			run(c, 30, 0);
			FrameBuffer fb = new FrameBuffer();
			fb.publish(c);
			assertEquals(240, fb.width());
			byte[] raw = new byte[GbaNative.WIDTH * GbaNative.HEIGHT * 4], canvas = new byte[raw.length];
			fb.copyTo(raw);
			fb.copyLetterboxed(canvas);
			assertArrayEquals(raw, canvas);
		}
	}

	@Test
	void saveStateAndKeysThroughTheBinding() {
		try (GbaCore c = core("gb_dmg.gb")) {
			run(c, 30, 0);
			byte[] before = c.getSave();
			assertNotNull(before);
			assertEquals(8192 + 48, before.length);
			for (int i = 0; i < 3; i++) { run(c, 3, 1); run(c, 3, 0); } // A x3 = bit 0 on a GBA key mask
			byte[] save = c.getSave();
			assertTrue(c.saveDirty() || save != null);
			assertEquals(3, save[6]);
			byte[] state = c.saveState();
			assertNotNull(state);
			try (GbaCore d = core("gb_dmg.gb")) {
				assertEquals(0, d.loadSave(save));
				run(d, 10, 0);
				assertTrue(d.loadState(state));
				assertFalse(d.loadState(Arrays.copyOf(state, state.length - 1)));
			}
		}
	}

	@Test
	void gameBoyCoreRefusesLinkCalls() {
		try (GbaCore c = core("gb_cgb.gbc")) {
			c.linkConfigure(0, 2); // ignored, no crash
			run(c, 10, 0);
			assertNull(c.linkPollOutgoing());
			c.linkDeliver(1, new byte[] {1, 1, 0, 2, 0, 0, 0, 0, 0});
			run(c, 5, 0);
		}
	}
}
