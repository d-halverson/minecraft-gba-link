package com.drew.gba.client.spike;

import java.util.Random;

/**
 * Discrete-event model of input-exchange netplay between two machines (inputs only, ~2 bytes/frame).
 *
 * Machine m starts frame f at max(end of its frame f-1, its paced slot, arrival of the OTHER machine's input for frame f).
 * That input was sampled and sent when the other machine started frame f-d (d = input delay in frames), so with d frames of
 * delay a network latency up to d*16.7 ms is hidden. Pacing slots advance 1/59.7275 s per frame and re-base when a machine falls
 * more than 100 ms behind (same rule as EmulatorSession.loop); behind-schedule frames run back to back (catch-up).
 * The TCP-like link delivers in order (head-of-line blocking): arrival_i = max(arrival_{i-1}, send_i + one-way delay_i).
 * One-way delay = RTT/2 + uniform(-jitter, +jitter), floored at 0.5 ms; optionally a loss spike (retransmit) adds
 * max(200 ms, 2 RTT) with the given probability.
 */
public final class InputDelayModel {
	public static final double P = 1000.0 / 59.7275;

	public record Result(double rttMs, double jitterMs, int delayFrames, double stallRate, double meanStallMs, double p99StallMs,
			double maxStallMs, double effectiveFps, double longStallsPerMin) {}

	public static Result simulate(double rttMs, double jitterMs, int d, int frames, double spikeProb, long seed, double frameCostMs) {
		Random rnd = new Random(seed);
		double[][] start = new double[2][frames];
		double[][] arrive = new double[2][frames]; // arrive[m][f]: input of machine (1-m) for frame f, at machine m
		double[] lastArr = new double[2];
		double[] prevEnd = new double[2];
		double[] slot = new double[2];
		int stalled = 0;
		double stallSum = 0, maxStall = 0, endAll = 0;
		double[] stalls = new double[frames * 2];
		int ns = 0, longStalls = 0;
		for (int f = 0; f < frames; f++) {
			double[] ready = new double[2];
			for (int m = 0; m < 2; m++) {
				if (f == 0) slot[m] = 0; else slot[m] += P;
				ready[m] = Math.max(prevEnd[m], slot[m]);
			}
			for (int m = 0; m < 2; m++) {
				double arr = 0;
				int src = f - d;
				if (src >= 0 && d >= 1) {
					// the other machine started frame src earlier in this loop (src < f)
					double sent = start[1 - m][src];
					double delay = Math.max(0.5, rttMs / 2 + (rnd.nextDouble() * 2 - 1) * jitterMs);
					if (spikeProb > 0 && rnd.nextDouble() < spikeProb) delay += Math.max(200, 2 * rttMs);
					lastArr[m] = Math.max(lastArr[m], sent + delay);
					arr = lastArr[m];
				} else if (d == 0) {
					arr = 0; // handled below
				}
				arrive[m][f] = arr;
			}
			if (d == 0) {
				// mutual dependency: both need the other's start of the same frame; fixed point of two iterations
				double s0 = ready[0], s1 = ready[1];
				for (int it = 0; it < 3; it++) {
					double d01 = Math.max(0.5, rttMs / 2 + (rnd.nextDouble() * 2 - 1) * jitterMs);
					double d10 = Math.max(0.5, rttMs / 2 + (rnd.nextDouble() * 2 - 1) * jitterMs);
					double n0 = Math.max(ready[0], s1 + d10), n1 = Math.max(ready[1], s0 + d01);
					s0 = n0; s1 = n1;
				}
				arrive[0][f] = s0; arrive[1][f] = s1;
			}
			for (int m = 0; m < 2; m++) {
				double s = Math.max(ready[m], arrive[m][f]);
				double stall = Math.max(0, arrive[m][f] - ready[m]);
				if (stall > 0.5) {
					stalled++;
					stallSum += stall;
					stalls[ns++] = stall;
					maxStall = Math.max(maxStall, stall);
					if (stall > 2 * P) longStalls++;
				}
				start[m][f] = s;
				prevEnd[m] = s + frameCostMs;
				if (s - slot[m] > 100) slot[m] = s; // lag reset
				endAll = Math.max(endAll, prevEnd[m]);
			}
		}
		java.util.Arrays.sort(stalls, 0, ns);
		double p99 = ns == 0 ? 0 : stalls[Math.min(ns - 1, (int) (ns * 0.99))];
		double total = frames * 2.0;
		return new Result(rttMs, jitterMs, d, stalled / total, ns == 0 ? 0 : stallSum / ns, p99, maxStall,
			frames / (endAll / 1000.0), longStalls / (endAll / 60000.0) / 2);
	}

	/** Smallest d whose stall rate is at most {@code maxRate} (-1 if none up to 30). */
	public static int minDelay(double rttMs, double jitterMs, double maxRate, int frames, double spikeProb, long seed) {
		for (int d = 1; d <= 30; d++) {
			if (simulate(rttMs, jitterMs, d, frames, spikeProb, seed, 2.5).stallRate() <= maxRate) return d;
		}
		return -1;
	}
}
