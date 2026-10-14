package com.drew.gba.save;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure rules shared by client and server: save revisions (SHA-256 of the .sav bytes), save size validation,
 * the suspend-state resume decision and the import acceptance rule. No Minecraft classes, unit tested.
 */
public final class SaveRules {
	public static final int MAX_SAVE = 512 * 1024;
	/** Length of the RTC footer mGBA appends to a .sav for cartridges with a clock. */
	public static final int RTC_FOOTER = 16;
	/** Sizes of the battery memories a GBA cartridge can have (EEPROM 512 B / 8 KiB, SRAM 32 KiB, Flash 64/128 KiB). */
	public static final int[] BASE_SIZES = {512, 8 * 1024, 32 * 1024, 64 * 1024, 128 * 1024};
	/** How many previous saves the server keeps when an import replaces one (<pak>.sav.bak1 is the newest). */
	public static final int IMPORT_BACKUPS = 3;

	private SaveRules() {}

	/** Revision of a save: lowercase hex SHA-256 of its bytes. A missing save is the revision of zero bytes. */
	public static String revision(byte[] save) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(save == null ? new byte[0] : save);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static final String EMPTY_REVISION = revision(new byte[0]);

	public static boolean isTypicalSize(int len) {
		for (int base : BASE_SIZES) if (len == base || len == base + RTC_FOOTER) return true;
		return false;
	}

	/** @return null if {@code len} is an acceptable save size for import, otherwise a player-facing reason */
	public static String validateImportSize(long len) {
		if (len <= 0) return "The save file is empty";
		if (len > MAX_SAVE) return "The save file is too large (max 512 KiB)";
		if (!isTypicalSize((int) len)) {
			return "Not a GBA save: " + len + " bytes (expected 512 B, 8, 32, 64 or 128 KiB, optionally +16 bytes RTC)";
		}
		return null;
	}

	public enum Resume {
		/** Load the suspend state. */
		RESUME,
		/** No suspend state on disk: cold boot. */
		NONE,
		/** The server save differs from the save the suspend was based on: discard it, cold boot. */
		DISCARD_CHANGED,
		/** Unreadable, or for a different ROM: discard it, cold boot. */
		DISCARD_INVALID
	}

	/**
	 * Decide what to do with a suspend state when a pak is opened.
	 * @param suspend decoded suspend file, or null if none/unreadable (see {@code unreadable})
	 * @param serverRevision revision of the save the server just sent
	 * @param romSha1 SHA-1 of the ROM about to be loaded
	 */
	public static Resume decide(SuspendFile suspend, boolean unreadable, String serverRevision, String romSha1) {
		if (unreadable) return Resume.DISCARD_INVALID;
		if (suspend == null) return Resume.NONE;
		if (serverRevision == null || serverRevision.isEmpty()) return Resume.DISCARD_CHANGED; // unknown: be safe
		if (!suspend.romSha1().equalsIgnoreCase(romSha1)) return Resume.DISCARD_INVALID;
		// Equal revisions mean byte-identical saves, whether or not the upload was ever acknowledged.
		return suspend.revision().equals(serverRevision) ? Resume.RESUME : Resume.DISCARD_CHANGED;
	}

	/**
	 * May the server accept an imported save for a pak?
	 * @param hasOpenSession the importing player currently has an open session for exactly this pak
	 * @return null if accepted, otherwise the reason
	 */
	public static String importRejection(boolean hasOpenSession, long length) {
		if (!hasOpenSession) return "Imports are only accepted while you have this cartridge open";
		return validateImportSize(length);
	}

	public static String safeFileName(String s) {
		String t = new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		if (t.isEmpty() || t.equals(".") || t.equals("..")) t = "game";
		return t.length() > 40 ? t.substring(0, 40) : t;
	}
}
