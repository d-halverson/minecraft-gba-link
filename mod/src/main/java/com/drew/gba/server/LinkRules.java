package com.drew.gba.server;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Which consoles can share one link cable (0.5.1). Pure logic, shared by the server and the client HUD.
 *
 * <ul>
 * <li>Game Boy Advance games link with Game Boy Advance games (2 to 4 players).</li>
 * <li>Game Boy and Game Boy Color games link with each other, GB with GB, GBC with GBC and GB with GBC (what the game
 * itself accepts is up to the game; Pokemon Gold / Silver / Crystal trade with Red / Blue / Yellow in the time capsule). A
 * Game Boy cable joins exactly two consoles.</li>
 * <li>A Game Boy game and a Game Boy Advance game can never be linked.</li>
 * </ul>
 */
public final class LinkRules {
	private LinkRules() {}

	public enum Family { GBA, GAME_BOY }

	public static Family family(String system) {
		return PakData.isGameBoy(system) ? Family.GAME_BOY : Family.GBA;
	}

	public static int maxPlayers(Family f) {
		return f == Family.GAME_BOY ? 2 : LinkRooms.MAX_MEMBERS;
	}

	public static boolean compatible(String a, String b) {
		return family(a) == family(b);
	}

	/** "Game Boy Advance" or "Game Boy / Game Boy Color". */
	public static String familyName(Family f) {
		return f == Family.GAME_BOY ? "Game Boy / Game Boy Color" : "Game Boy Advance";
	}

	/**
	 * The members that take part in the link out of the candidates (room order, every candidate has a game open and can link
	 * it): the biggest group of one family with at least two members (a tie goes to the family whose first member comes first
	 * in the room), cut to the family's maximum (2 for Game Boy). Empty if no family has two members.
	 */
	public static <K> List<K> select(List<K> candidates, Function<K, String> system) {
		List<K> gba = new ArrayList<>(), gb = new ArrayList<>();
		K firstGba = null, firstGb = null;
		int posGba = -1, posGb = -1;
		for (int i = 0; i < candidates.size(); i++) {
			K k = candidates.get(i);
			if (family(system.apply(k)) == Family.GAME_BOY) {
				if (gb.isEmpty()) posGb = i;
				gb.add(k);
			} else {
				if (gba.isEmpty()) posGba = i;
				gba.add(k);
			}
		}
		int usableGba = gba.size(), usableGb = Math.min(gb.size(), maxPlayers(Family.GAME_BOY));
		List<K> pick;
		if (usableGba < 2 && usableGb < 2) return List.of();
		if (usableGba > usableGb || (usableGba == usableGb && posGba < posGb)) pick = gba;
		else pick = gb;
		if (usableGba < 2) pick = gb;
		if (usableGb < 2) pick = gba;
		int max = maxPlayers(pick == gb ? Family.GAME_BOY : Family.GBA);
		return pick.size() > max ? new ArrayList<>(pick.subList(0, max)) : pick;
	}

	/** Why a member with a game open is not part of the link. */
	public enum Exclusion { NONE, OTHER_SYSTEM, TOO_MANY }

	public static <K> Exclusion exclusion(K member, List<K> candidates, List<K> selected, Function<K, String> system) {
		if (candidates.size() < 2 || !candidates.contains(member) || selected.contains(member)) return Exclusion.NONE;
		if (selected.isEmpty()) return Exclusion.OTHER_SYSTEM; // a lone player of this family: the others are of another one
		return family(system.apply(member)) == family(system.apply(selected.get(0))) ? Exclusion.TOO_MANY : Exclusion.OTHER_SYSTEM;
	}

	/**
	 * Text for a player who cannot be linked right now because of the games being played, e.g. "Can't link: Bob plays a Game
	 * Boy Advance game, you play a Game Boy game." {@code mine} is the player's system, {@code others} (names and systems,
	 * same order) are the other members with a game open. Returns null when there is nothing to say.
	 */
	public static String mismatchMessage(String mine, List<String> names, List<String> systems) {
		Family me = family(mine);
		List<String> other = new ArrayList<>();
		for (int i = 0; i < names.size() && i < systems.size(); i++) {
			String s = systems.get(i);
			if (s == null || s.isEmpty()) continue;
			if (family(s) != me) other.add(names.get(i));
		}
		if (other.isEmpty()) return null;
		String who = String.join(", ", other);
		return "Can't link: " + who + (other.size() == 1 ? " plays" : " play") + " a " + (me == Family.GAME_BOY ? "Game Boy Advance" : "Game Boy / Color")
			+ " game, you play a " + (me == Family.GAME_BOY ? "Game Boy / Color" : "Game Boy Advance") + " game";
	}

	/** Shown when a Game Boy cable already joins two players. */
	public static String tooManyMessage(List<String> linked) {
		return "A Game Boy link cable joins two players (" + String.join(" and ", linked) + " are linked)";
	}
}
