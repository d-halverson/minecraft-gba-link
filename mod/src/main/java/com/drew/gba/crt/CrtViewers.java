package com.drew.gba.crt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure viewer selection for one CRT: nearest first within range, capped. */
public final class CrtViewers {
	private CrtViewers() {}

	public record Candidate<T>(T id, double distance) {}

	public record Selection<T>(List<T> viewers, List<T> limited) {}

	/**
	 * @param candidates every eligible player (modded, same dimension, not the streamer) with its distance to the TV
	 * @return the nearest {@code max} within {@code range} as viewers; the rest within range as "limit reached"
	 */
	public static <T> Selection<T> select(List<Candidate<T>> candidates, int max, double range) {
		List<Candidate<T>> sorted = new ArrayList<>();
		for (Candidate<T> c : candidates) if (c.distance() <= range) sorted.add(c);
		sorted.sort(Comparator.comparingDouble(Candidate::distance));
		List<T> viewers = new ArrayList<>(), limited = new ArrayList<>();
		for (Candidate<T> c : sorted) (viewers.size() < max ? viewers : limited).add(c.id());
		return new Selection<>(viewers, limited);
	}
}
