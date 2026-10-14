package com.drew.gba.client.spike;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/** Shared helpers for the dual-simulation feasibility spike (test sources only). */
public final class SpikeSupport {
	private SpikeSupport() {}

	/** Offsets inside mGBA's raw save state blob (0x61000 bytes), found empirically (see PairSimulationSpikeTest). */
	public static final int STATE_SIZE = 0x61000, EWRAM = 0x21000, EWRAM_SIZE = 0x40000, IWRAM = 0x19000, IWRAM_SIZE = 0x8000,
		VRAM = 0x1000, VRAM_SIZE = 0x18000;

	/** EWRAM variable offsets of the pokesim ROM (native/tests/make_link_rom.py). */
	public static final int V_GOOD = 0, V_BAD = 4, V_MISSED = 8, V_SEQ = 0xC, V_PARTNER = 0x10, V_FRAMES = 0x14, V_ERR = 0x18,
		V_ERRORS = 0x1C, V_DUP = 0x20, V_SKIP = 0x24, V_SINCE = 0x28, V_MAXGAP = 0x30, V_TOTAL = 0x34, V_ROLE = 0x38, V_HASH = 0x3C;

	/** Loads libgbacore from ../dist/natives into gameDir and returns the binding, or null if it is not built. */
	public static GbaNative loadNative(Path gameDir) throws IOException {
		String platform = GbaNative.platform();
		String lib = platform.startsWith("macos") ? "libgbacore.dylib" : platform.startsWith("windows") ? "gbacore.dll" : "libgbacore.so";
		Path src = Path.of("..", "dist", "natives", platform, lib).toAbsolutePath().normalize();
		if (!Files.isRegularFile(src)) return null;
		Path dir = gameDir.resolve("gbalink").resolve("natives").resolve(platform);
		Files.createDirectories(dir);
		Files.copy(src, dir.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
		return GbaNative.get(gameDir);
	}

	public static Path extractRom(Path dir, String name) throws IOException {
		Files.createDirectories(dir);
		Path out = dir.resolve(name);
		if (Files.exists(out)) return out;
		try (InputStream in = SpikeSupport.class.getResourceAsStream("/roms/" + name)) {
			if (in == null) throw new IOException("missing test ROM resource " + name);
			Files.copy(in, out);
		}
		return out;
	}

	public static long word(byte[] state, int ewramOff) {
		return ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getInt(EWRAM + ewramOff) & 0xFFFFFFFFL;
	}

	public static void putWord(byte[] state, int ewramOff, long v) {
		ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).putInt(EWRAM + ewramOff, (int) v);
	}

	public static long crc(byte[] b, int off, int len) {
		CRC32 c = new CRC32();
		c.update(b, off, len);
		return c.getValue();
	}

	/** Hash of EWRAM + IWRAM + VRAM of a save state (what a periodic resync check would exchange). */
	public static long memHash(byte[] state) {
		CRC32 c = new CRC32();
		c.update(state, VRAM, VRAM_SIZE);
		c.update(state, IWRAM, IWRAM_SIZE);
		c.update(state, EWRAM, EWRAM_SIZE);
		return c.getValue();
	}

	public static long fbHash(GbaCore core, byte[] scratch) {
		core.copyFramebuffer(scratch);
		return crc(scratch, 0, scratch.length);
	}

	public record Counters(long good, long bad, long missed, long frames, long err, long errors, long dup, long skip, long maxGap, long total, long role, long hash) {
		public static Counters of(byte[] st) {
			return new Counters(word(st, V_GOOD), word(st, V_BAD), word(st, V_MISSED), word(st, V_FRAMES), word(st, V_ERR),
				word(st, V_ERRORS), word(st, V_DUP), word(st, V_SKIP), word(st, V_MAXGAP), word(st, V_TOTAL), word(st, V_ROLE), word(st, V_HASH));
		}

		@Override
		public String toString() {
			return "good=" + good + " bad=" + bad + " missed=" + missed + " frames=" + frames + " err=" + err + "/" + errors
				+ " dup=" + dup + " skip=" + skip + " maxGap=" + maxGap + " total=" + total;
		}
	}

	/** Deterministic scripted controller input: changes every 7-30 frames, a few buttons. */
	public static int script(int player, long frame, long seed) {
		if (frame < 0) return 0;
		long block = frame / 16;
		long h = (block + 1) * 0x9E3779B97F4A7C15L ^ (seed * 31 + player) * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
		return (int) (h & 0x3F3L); // A, B, Select-less, plus direction bits
	}
}
