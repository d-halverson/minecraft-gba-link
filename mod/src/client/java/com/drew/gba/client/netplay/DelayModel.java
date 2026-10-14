package com.drew.gba.client.netplay;

import java.util.ArrayDeque;

/**
 * Adaptive input delay proposal: ceil((p99 RTT / 2 + margin) / frame time), clamped to 2..10 frames, from a sliding window
 * of round trip samples. Raised immediately on a stall, lowered by one after 10 s without stalls. The engine sends the
 * proposal inside its input records; the delay that is actually used is the maximum of all players' proposals.
 */
public final class DelayModel {
	public static final double FRAME_MS = 1000.0 / 59.7275;
	public static final int MIN = 2, MAX = 10;
	private static final long WINDOW_NANOS = 5_000_000_000L, LOWER_AFTER_NANOS = 10_000_000_000L, RAISE_GAP_NANOS = 1_000_000_000L;

	private record Sample(long at, double ms) {}

	private final ArrayDeque<Sample>[] samples;
	private final double marginMs;
	private int desired;
	private long lastStall = (Long.MIN_VALUE / 2), lastChange, lastRaise = (Long.MIN_VALUE / 2);
	private volatile double pingMs;
	private volatile int baseline;

	@SuppressWarnings("unchecked")
	public DelayModel(int players, int initial, double marginMs, long now) {
		samples = new ArrayDeque[Math.max(1, players)];
		for (int i = 0; i < samples.length; i++) samples[i] = new ArrayDeque<>();
		this.marginMs = marginMs;
		this.desired = clamp(initial);
		this.lastChange = now;
		this.baseline = this.desired;
	}

	public static int clamp(int d) { return Math.max(MIN, Math.min(MAX, d)); }

	/** Delay in frames needed for a given round trip percentile (ms). */
	public int framesFor(double p99RttMs) {
		return clamp((int) Math.ceil((p99RttMs / 2 + marginMs) / FRAME_MS));
	}

	public void addRtt(int peer, double ms, long now) {
		if (peer < 0 || peer >= samples.length || ms < 0 || ms > 5000) return;
		samples[peer].add(new Sample(now, ms));
	}

	/** Current proposal in frames. */
	public int proposal() { return desired; }
	public int baseline() { return baseline; }
	/** Median round trip of the slowest peer (ms), 0 if unknown. */
	public double pingMs() { return pingMs; }

	public void onStall(long now, int currentDelay) {
		lastStall = now;
		if (now - lastRaise >= RAISE_GAP_NANOS) {
			desired = Math.min(MAX, Math.max(desired, currentDelay) + 1);
			lastRaise = now;
			lastChange = now;
		}
	}

	/** Call about once per frame. */
	public void update(long now) {
		double worstP99 = -1, worstMedian = 0;
		int count = 0;
		for (ArrayDeque<Sample> q : samples) {
			while (!q.isEmpty() && now - q.peekFirst().at > WINDOW_NANOS) q.pollFirst();
			if (q.size() < 3) continue;
			double[] v = new double[q.size()];
			int i = 0;
			for (Sample s : q) v[i++] = s.ms;
			java.util.Arrays.sort(v);
			worstP99 = Math.max(worstP99, v[Math.min(v.length - 1, (int) (v.length * 0.99))]);
			worstMedian = Math.max(worstMedian, v[v.length / 2]);
			count += v.length;
		}
		if (count < 5) return;
		pingMs = worstMedian;
		int base = framesFor(worstP99);
		baseline = base;
		if (base > desired) {
			desired = base;
			lastChange = now;
			lastRaise = now;
		} else if (desired > base && now - lastChange >= LOWER_AFTER_NANOS && now - lastStall >= LOWER_AFTER_NANOS) {
			desired--;
			lastChange = now;
		}
	}
}
