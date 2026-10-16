package com.drew.gba.server;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validated pak description (what lives inside a Pak item / a GBA's inserted slot). {@code system} (0.5.0, optional:
 * paks made before default to GBA) is {@link #SYS_GBA}, {@link #SYS_GB} (Game Boy) or {@link #SYS_GBC} (Game Boy Color).
 */
public record PakData(UUID pakId, String sha1, String title, String code, String system) {
	public static final String SYS_GBA = "gba", SYS_GB = "gb", SYS_GBC = "gbc";
	private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

	public PakData {
		system = normalizeSystem(system);
	}

	/** A Game Boy Advance pak (what every pak was before 0.5.0). */
	public PakData(UUID pakId, String sha1, String title, String code) {
		this(pakId, sha1, title, code, SYS_GBA);
	}

	/** Unknown, empty or missing values become {@code gba}. */
	public static String normalizeSystem(String s) {
		if (s == null) return SYS_GBA;
		String v = s.trim().toLowerCase(Locale.ROOT);
		return v.equals(SYS_GB) || v.equals(SYS_GBC) ? v : SYS_GBA;
	}

	/** True for Game Boy and Game Boy Color paks. */
	public boolean gameBoy() {
		return isGameBoy(system);
	}

	public static boolean isGameBoy(String system) {
		String v = normalizeSystem(system);
		return v.equals(SYS_GB) || v.equals(SYS_GBC);
	}

	/** Human readable system name for tooltips. */
	public static String systemName(String system) {
		return switch (normalizeSystem(system)) {
			case SYS_GB -> "Game Boy";
			case SYS_GBC -> "Game Boy Color";
			default -> "Game Boy Advance";
		};
	}

	public static boolean validSha1(String s) {
		return s != null && SHA1.matcher(s.toLowerCase(Locale.ROOT)).matches();
	}

	/** Strip control/formatting characters, collapse whitespace, cap at 32 chars. */
	public static String sanitizeTitle(String raw) {
		if (raw == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length() && sb.length() < 32; i++) {
			char c = raw.charAt(i);
			if (c == '\u00a7' || Character.isSurrogate(c)) continue;
			if (Character.isISOControl(c)) c = ' ';
			sb.append(c);
		}
		return sb.toString().trim().replaceAll("\\s+", " ");
	}

	public static String sanitizeCode(String raw) {
		if (raw == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length() && sb.length() < 4; i++) {
			char c = raw.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
		}
		return sb.toString();
	}
}
