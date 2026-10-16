package com.drew.gba.rom;

import java.util.Locale;

/**
 * Header facts of a ROM file: system, title and game code, read exactly like the native library does
 * ({@code gba_rom_info}, see gbacore.h) so the ROM list, the burned pak and the running core agree. Pure Java,
 * no Minecraft classes, unit tested.
 *
 * <p>Systems: {@link #GBA}, {@link #GB} (monochrome Game Boy game) and {@link #GBC} (Game Boy Color game: the cartridge
 * header's CGB flag, 0x80 or 0xC0 at 0x143, regardless of the file extension).
 */
public record RomHeader(String system, String title, String code) {
	public static final String GBA = "gba", GB = "gb", GBC = "gbc";
	public static final long MAX_GBA_BYTES = 32L * 1024 * 1024;
	public static final long MAX_GB_BYTES = 8L * 1024 * 1024;
	/** Bytes of the file start {@link #parse} needs. */
	public static final int HEAD_BYTES = 0x150;

	/** Parse result: either a header or a player-facing reason why the file is not a valid ROM of its type. */
	public record Result(RomHeader header, String error, String warning) {
		public Result(RomHeader header, String error) { this(header, error, ""); }
		public boolean ok() { return header != null; }
	}

	/** Which system a file name suggests ({@link #GBA} or {@link #GB}), or null if it is not a ROM extension. */
	public static String systemForFileName(String fileName) {
		String n = fileName.toLowerCase(Locale.ROOT);
		if (n.endsWith(".gba")) return GBA;
		if (n.endsWith(".gb") || n.endsWith(".gbc")) return GB;
		return null;
	}

	/** Short tag for lists: {@code GBA}, {@code GB}, {@code GBC}. */
	public static String tag(String system) {
		return system == null ? "GBA" : system.toUpperCase(Locale.ROOT);
	}

	/** Long name for tooltips. */
	public static String systemName(String system) {
		return switch (system == null ? GBA : system) {
			case GB -> "Game Boy";
			case GBC -> "Game Boy Color";
			default -> "Game Boy Advance";
		};
	}

	/**
	 * @param fileName the file's name (its extension picks the header layout)
	 * @param size the file size in bytes
	 * @param head the first bytes of the file (at least {@link #HEAD_BYTES} if the file is that long)
	 */
	public static Result parse(String fileName, long size, byte[] head) {
		String sys = systemForFileName(fileName);
		if (sys == null) return new Result(null, "Not a .gba, .gb or .gbc file");
		return GBA.equals(sys) ? parseGba(size, head) : parseGb(size, head);
	}

	private static Result parseGba(long size, byte[] h) {
		if (size > MAX_GBA_BYTES) return new Result(null, "Too large for a GBA game (" + mib(size) + ", max 32 MiB)");
		if (size < 0xC0 || h.length < 0xC0) return new Result(null, "Too small to be a GBA ROM (no header)");
		if ((h[0xB2] & 0xFF) != 0x96) return new Result(null, "Not a GBA ROM: the header's fixed value (0x96 at 0xB2) is missing");
		// The complement checksum at 0xBD is not enforced for GBA: patched games and homebrew often leave it stale and mGBA
		// (like most emulators) ignores it, so refusing them would only lock out games that did work before 0.5.0.
		return new Result(new RomHeader(GBA, ascii(h, 0xA0, 12), ascii(h, 0xAC, 4)), null);
	}

	private static final byte[] LOGO_START = {(byte) 0xCE, (byte) 0xED, 0x66, 0x66};

	private static Result parseGb(long size, byte[] h) {
		if (size > MAX_GB_BYTES) return new Result(null, "Too large for a Game Boy game (" + mib(size) + ", max 8 MiB)");
		if (size < HEAD_BYTES || h.length < HEAD_BYTES) return new Result(null, "Too small to be a Game Boy ROM (no header)");
		for (int i = 0; i < LOGO_START.length; i++) {
			if (h[0x104 + i] != LOGO_START[i]) return new Result(null, "Not a Game Boy ROM: the Nintendo logo is missing from the header");
		}
		int x = 0;
		for (int i = 0x134; i <= 0x14C; i++) x = (x - (h[i] & 0xFF) - 1) & 0xFF;
		if (x != (h[0x14D] & 0xFF)) return new Result(null, "Not a valid Game Boy ROM: header checksum mismatch");
		boolean color = (h[0x143] & 0x80) != 0;
		// A dump that is shorter than its header says (a cut-off download) still loads, but cannot run the game: warn.
		String warn = "";
		int sizeCode = h[0x148] & 0xFF;
		if (sizeCode <= 8) {
			long declared = 32768L << sizeCode;
			if (size < declared) {
				warn = "ROM looks incomplete: the file is " + kib(size) + ", the header expects " + kib(declared) + "; the game probably won't run";
			}
		}
		return new Result(new RomHeader(color ? GBC : GB, ascii(h, 0x134, 11), gbCode(h)), null, warn);
	}

	/** The 4 character game code of a Game Boy header (same rule as gba_rom_info in the native library). */
	static String gbCode(byte[] h) {
		boolean mfr = true;
		for (int i = 0x13F; i <= 0x142; i++) {
			int c = h[i] & 0xFF;
			mfr &= (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
		}
		if (mfr) return new String(h, 0x13F, 4, java.nio.charset.StandardCharsets.US_ASCII);
		return "G" + String.format("%02X", h[0x14D] & 0xFF) + Integer.toHexString(h[0x14F] & 0xF).toUpperCase(Locale.ROOT);
	}

	private static String ascii(byte[] b, int off, int len) {
		StringBuilder sb = new StringBuilder();
		for (int i = off; i < off + len; i++) {
			int c = b[i] & 0xFF;
			if (c < 0x20 || c >= 0x7F) break; // stops at the first NUL / non-printable byte, like the native side
			sb.append((char) c);
		}
		return sb.toString().trim();
	}

	private static String kib(long bytes) {
		return bytes / 1024 + " KB";
	}

	private static String mib(long bytes) {
		return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
	}
}
