package com.drew.gba.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Link cable rooms. Pure logic keyed by an opaque member id so it can be unit tested. */
public final class LinkRooms<K> {
	public static final int MAX_MEMBERS = 4;

	/** Result of a room change: the surviving room (empty if dissolved) and members that just left any room. */
	public record Update<K>(List<K> room, List<K> released) {}

	public enum LinkResult { LINKED, ALREADY_LINKED, ROOM_FULL, OTHER_ROOM, SELF }

	private final Map<K, List<K>> roomOf = new HashMap<>();

	public List<K> room(K member) {
		List<K> r = roomOf.get(member);
		return r == null ? List.of() : List.copyOf(r);
	}

	public int indexOf(K member) {
		List<K> r = roomOf.get(member);
		return r == null ? -1 : r.indexOf(member);
	}

	public boolean isLinked(K member) {
		return roomOf.containsKey(member);
	}

	/** {@code user} uses a cable on {@code target}. Updates are appended to {@code out} on success. */
	public LinkResult link(K user, K target, List<Update<K>> out) {
		if (user.equals(target)) return LinkResult.SELF;
		List<K> ur = roomOf.get(user);
		List<K> tr = roomOf.get(target);
		if (ur != null && ur == tr) return LinkResult.ALREADY_LINKED;
		if (ur != null && tr != null) return LinkResult.OTHER_ROOM;
		List<K> room;
		if (ur != null) {
			room = ur;
			if (room.size() >= MAX_MEMBERS) return LinkResult.ROOM_FULL;
			room.add(target);
			roomOf.put(target, room);
		} else if (tr != null) {
			room = tr;
			if (room.size() >= MAX_MEMBERS) return LinkResult.ROOM_FULL;
			room.add(user);
			roomOf.put(user, room);
		} else {
			room = new ArrayList<>();
			room.add(user);
			room.add(target);
			roomOf.put(user, room);
			roomOf.put(target, room);
		}
		out.add(new Update<>(List.copyOf(room), List.of()));
		return LinkResult.LINKED;
	}

	/** Remove a member from its room (disconnect, unlink, out of range). Others are re-indexed implicitly. */
	public void remove(K member, List<Update<K>> out) {
		List<K> room = roomOf.remove(member);
		if (room == null) return;
		room.remove(member);
		if (room.size() >= 2) {
			out.add(new Update<>(List.copyOf(room), List.of(member)));
		} else {
			List<K> released = new ArrayList<>();
			released.add(member);
			for (K k : room) {
				roomOf.remove(k);
				released.add(k);
			}
			out.add(new Update<>(List.of(), released));
		}
	}

	/** All distinct rooms (snapshot), for periodic range checks. */
	public List<List<K>> allRooms() {
		List<List<K>> rooms = new ArrayList<>();
		for (List<K> r : roomOf.values()) {
			boolean seen = false;
			for (List<K> o : rooms) if (o.equals(r)) { seen = true; break; }
			if (!seen) rooms.add(List.copyOf(r));
		}
		return rooms;
	}
}
