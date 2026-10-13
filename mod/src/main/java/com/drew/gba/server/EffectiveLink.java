package com.drew.gba.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The link the clients actually see: only room members with an open emulator take part, indexed
 * 0..n-1 in room join order. Fewer than two active members means nobody is plugged in.
 */
public final class EffectiveLink {
	private EffectiveLink() {}

	public record State(int index, int count) {
		public static final State UNPLUGGED = new State(0, 0);
		public boolean plugged() { return count >= 2; }
	}

	/** Active members in room order, or empty if fewer than two are active. */
	public static <K> List<K> active(List<K> room, Predicate<K> open) {
		List<K> a = new ArrayList<>();
		for (K k : room) if (open.test(k)) a.add(k);
		return a.size() >= 2 ? a : List.of();
	}

	/** Effective state of every room member (inactive members and small groups are UNPLUGGED). */
	public static <K> Map<K, State> states(List<K> room, Predicate<K> open) {
		List<K> a = active(room, open);
		Map<K, State> m = new LinkedHashMap<>();
		for (K k : room) {
			int i = a.indexOf(k);
			m.put(k, i < 0 ? State.UNPLUGGED : new State(i, a.size()));
		}
		return m;
	}

	/** Effective index of a member, or -1 if it is not part of an effective link. */
	public static <K> int indexOf(List<K> room, Predicate<K> open, K member) {
		return active(room, open).indexOf(member);
	}
}
