package com.drew.gba.server;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts relayed link messages per sender (written from the Netty thread, read from the server tick) and
 * rate-limits the per-room summary lines in latest.log to one per room per period.
 */
public final class RelayCounters {
	private final Map<UUID, LongAdder> perSender = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastTotal = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastLogMs = new ConcurrentHashMap<>();
	private final long periodMs;

	public RelayCounters(long periodMs) {
		this.periodMs = periodMs;
	}

	public void count(UUID sender) {
		perSender.computeIfAbsent(sender, k -> new LongAdder()).increment();
	}

	public void forget(UUID sender) {
		perSender.remove(sender);
		lastTotal.remove(sender);
		lastLogMs.remove(sender);
	}

	public record Rate(long messages, double perSecond, double seconds) {}

	/**
	 * Called regularly with a room's members (the first member identifies the room). Returns the rate since the last
	 * report once {@code periodMs} has passed and at least one message was relayed, else null (idle rooms stay silent).
	 */
	public Rate poll(List<UUID> room, long nowMs) {
		if (room.isEmpty()) return null;
		UUID key = room.get(0);
		Long last = lastLogMs.get(key);
		if (last == null) {
			lastLogMs.put(key, nowMs);
			lastTotal.put(key, total(room));
			return null;
		}
		if (nowMs - last < periodMs) return null;
		long total = total(room);
		long delta = total - lastTotal.getOrDefault(key, 0L);
		lastLogMs.put(key, nowMs);
		lastTotal.put(key, total);
		if (delta <= 0) return null;
		double sec = (nowMs - last) / 1000.0;
		return new Rate(delta, delta / sec, sec);
	}

	private long total(List<UUID> room) {
		long t = 0;
		for (UUID u : room) {
			LongAdder a = perSender.get(u);
			if (a != null) t += a.sum();
		}
		return t;
	}
}
