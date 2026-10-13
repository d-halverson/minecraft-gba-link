package com.drew.gba.server;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validated pak description (what lives inside a Pak item / a GBA's inserted slot). */
public record PakData(UUID pakId, String sha1, String title, String code) {
	private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

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
