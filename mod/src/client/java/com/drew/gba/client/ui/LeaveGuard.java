package com.drew.gba.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * When does leaving the GBA / CRT need a confirmation, and what does the popup say. No Minecraft classes (unit-tested).
 * Leaving ends the link for everyone: the partner's game shows "Communication error".
 */
public final class LeaveGuard {
	private LeaveGuard() {}

	/**
	 * True while a link is active or being set up: netplay running or expected, or a classic link with two or more
	 * consoles that is still answering. A finished session never needs a confirmation.
	 */
	public static boolean needsConfirm(boolean finished, boolean netplayActive, boolean netplayExpected, int linkCount, boolean linkTimedOut) {
		if (finished) return false;
		return netplayActive || netplayExpected || (linkCount >= 2 && !linkTimedOut);
	}

	/** "A", "A and B", "A, B and C"; "your partner" when no names are known. */
	public static String joinNames(List<String> names) {
		List<String> n = new ArrayList<>();
		for (String s : names) if (s != null && !s.isBlank()) n.add(s);
		if (n.isEmpty()) return "your partner";
		if (n.size() == 1) return n.get(0);
		return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
	}

	public static String message(List<String> partners) {
		return "You're linked with " + joinNames(partners) + ". Leaving now disconnects the link cable and the game will show a "
			+ "communication error. Leave anyway?";
	}

	/** Greedy word wrap; {@code width} measures a string in pixels. Long single words stay whole. */
	public static List<String> wrap(String text, int maxWidth, java.util.function.ToIntFunction<String> width) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String w : text.split(" ")) {
			String next = line.length() == 0 ? w : line + " " + w;
			if (line.length() > 0 && width.applyAsInt(next) > maxWidth) {
				out.add(line.toString());
				line = new StringBuilder(w);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (line.length() > 0) out.add(line.toString());
		return out;
	}
}
