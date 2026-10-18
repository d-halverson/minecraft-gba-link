package com.drew.gba.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The link the clients actually see: only room members with an open emulator take part, indexed
 * 0..n-1 in room join order, and only members whose games can share a cable ({@link LinkRules}). Fewer than two
 * active members means nobody is plugged in.
 */
public final class EffectiveLink {
	private EffectiveLink() {}

	/** {@code netplay}: the members run input-sync netplay (all paks have the same ROM) instead of the classic link. */
	public record State(int index, int count, boolean netplay) {
		public State(int index, int count) { this(index, count, false); }
		public static final State UNPLUGGED = new State(0, 0, false);
		public boolean plugged() { return count >= 2; }
	}

	/** Active members in room order, or empty if fewer than two are active (every game counts as a Game Boy Advance game). */
	public static <K> List<K> active(List<K> room, Predicate<K> open) {
		return active(room, open, k -> null);
	}

	/**
	 * Active members: those with a game open that can link ({@code open}), reduced to the ones that can share a cable
	 * ({@link LinkRules#select}: GBA with GBA, Game Boy / Color with Game Boy / Color, two at most). Empty if fewer than two.
	 */
	public static <K> List<K> active(List<K> room, Predicate<K> open, Function<K, String> system) {
		List<K> a = new ArrayList<>();
		for (K k : room) if (open.test(k)) a.add(k);
		List<K> sel = LinkRules.select(a, system);
		return sel.size() >= 2 ? sel : List.of();
	}

	/** Effective state of every room member (inactive members and small groups are UNPLUGGED). */
	public static <K> Map<K, State> states(List<K> room, Predicate<K> open) {
		return states(room, open, false);
	}

	public static <K> Map<K, State> states(List<K> room, Predicate<K> open, boolean netplay) {
		return states(room, open, netplay, k -> null);
	}

	public static <K> Map<K, State> states(List<K> room, Predicate<K> open, boolean netplay, Function<K, String> system) {
		List<K> a = active(room, open, system);
		Map<K, State> m = new LinkedHashMap<>();
		for (K k : room) {
			int i = a.indexOf(k);
			m.put(k, i < 0 ? State.UNPLUGGED : new State(i, a.size(), netplay));
		}
		return m;
	}

	/**
	 * Netplay needs at least two active players, every client able to run it, and the same ROM (SHA-1) in every pak.
	 * Anything else uses the classic lockstep link ("different games - slow mode").
	 */
	public static <K> boolean netplay(List<K> active, java.util.function.Function<K, String> sha1, Predicate<K> capable) {
		if (active.size() < 2) return false;
		String first = null;
		for (K k : active) {
			String s = sha1.apply(k);
			if (s == null || s.isEmpty() || !capable.test(k)) return false;
			if (first == null) first = s;
			else if (!first.equalsIgnoreCase(s)) return false;
		}
		return true;
	}

	/** Effective index of a member, or -1 if it is not part of an effective link. */
	public static <K> int indexOf(List<K> room, Predicate<K> open, K member) {
		return active(room, open).indexOf(member);
	}

	public static <K> int indexOf(List<K> room, Predicate<K> open, Function<K, String> system, K member) {
		return active(room, open, system).indexOf(member);
	}
}
