package com.drew.gba.stream;

import java.util.Arrays;

/**
 * 32768 Hz stereo int16 in, 16 kHz mono IMA-ADPCM packet out (~8 KB/s).
 *
 * <p>Downmix to mono, then a 40-tap windowed-sinc resampler (cutoff ~6.8 kHz) with fractional phase carried across
 * calls, then 4-bit IMA ADPCM with the predictor carried across calls. Packet layout (little-endian):
 * magic 0xA1, step index u8, predictor i16 (decoder state <em>before</em> the first nibble), sample count u16,
 * then ceil(count/2) bytes, low nibble first. Every packet therefore decodes on its own.
 *
 * <p>Not thread safe: one encoder per stream. {@link #encode} always returns a valid packet (possibly 0 samples).
 */
public final class AudioEncoder {
	static final int MAGIC = 0xA1, HEADER = 6;
	public static final int IN_RATE = 32768, OUT_RATE = 16000;
	private static final int HALF = 20; // kernel half-width in input samples
	private static final int PHASES = 256;
	private static final float[][] KERNEL = new float[PHASES + 1][2 * HALF];
	private static final double RATIO = (double) IN_RATE / OUT_RATE;

	static {
		double fc = 6800.0 / IN_RATE; // cycles per input sample
		for (int ph = 0; ph <= PHASES; ph++) {
			double frac = (double) ph / PHASES, sum = 0;
			double[] k = new double[2 * HALF];
			for (int i = 0; i < 2 * HALF; i++) {
				double d = (i - (HALF - 1)) - frac; // input sample offset from the output instant
				double s = d == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * d) / (Math.PI * d);
				double w = 0.42 + 0.5 * Math.cos(Math.PI * d / HALF) + 0.08 * Math.cos(2 * Math.PI * d / HALF);
				if (Math.abs(d) >= HALF) w = 0;
				k[i] = s * w;
				sum += k[i];
			}
			for (int i = 0; i < 2 * HALF; i++) KERNEL[ph][i] = (float) (k[i] / sum);
		}
	}

	private float[] buf = new float[4096]; // mono input history + pending
	private int len = HALF; // zeros as history before the first sample
	private double pos = HALF - 1; // input position of the next output instant (index into buf)
	private int predictor, stepIndex;
	private short[] mono = new short[2048];

	public byte[] encode(short[] stereo32k, int frames) {
		if (stereo32k == null) frames = 0;
		else frames = Math.max(0, Math.min(frames, stereo32k.length / 2));
		if (len + frames + 2 * HALF > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + frames + 4 * HALF));
		for (int i = 0; i < frames; i++) buf[len++] = (stereo32k[2 * i] + stereo32k[2 * i + 1]) * 0.5f;
		// resample as many output samples as the buffered input allows
		int maxOut = (int) ((len - pos) / RATIO) + 2;
		if (mono.length < maxOut) mono = new short[maxOut + 64];
		int n = 0;
		while (true) {
			int base = (int) pos;
			if (base + HALF >= len) break;
			float[] k = KERNEL[(int) ((pos - base) * PHASES + 0.5)];
			int start = base - (HALF - 1);
			float acc = 0;
			for (int i = 0; i < 2 * HALF; i++) acc += buf[start + i] * k[i];
			int v = Math.round(acc);
			mono[n++] = (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
			pos += RATIO;
		}
		// drop consumed history, keep HALF-1 samples before the next instant
		int keepFrom = Math.max(0, (int) pos - (HALF - 1));
		if (keepFrom > 0) {
			System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom);
			len -= keepFrom;
			pos -= keepFrom;
		}
		return adpcm(n);
	}

	private byte[] adpcm(int n) {
		if (n > 65535) n = 65535;
		byte[] out = new byte[HEADER + (n + 1) / 2];
		out[0] = (byte) MAGIC;
		out[1] = (byte) stepIndex;
		out[2] = (byte) predictor;
		out[3] = (byte) (predictor >> 8);
		out[4] = (byte) n;
		out[5] = (byte) (n >> 8);
		int pred = predictor, idx = stepIndex;
		for (int i = 0; i < n; i++) {
			int step = ImaAdpcm.STEP[idx];
			int diff = mono[i] - pred;
			int code = 0;
			if (diff < 0) {
				code = 8;
				diff = -diff;
			}
			int vp = step >> 3;
			if (diff >= step) { code |= 4; diff -= step; vp += step; }
			if (diff >= (step >> 1)) { code |= 2; diff -= step >> 1; vp += step >> 1; }
			if (diff >= (step >> 2)) { code |= 1; vp += step >> 2; }
			pred += (code & 8) != 0 ? -vp : vp;
			pred = pred > 32767 ? 32767 : pred < -32768 ? -32768 : pred;
			idx += ImaAdpcm.INDEX[code & 7];
			idx = idx < 0 ? 0 : idx > 88 ? 88 : idx;
			if ((i & 1) == 0) out[HEADER + (i >> 1)] = (byte) code;
			else out[HEADER + (i >> 1)] |= (byte) (code << 4);
		}
		predictor = pred;
		stepIndex = idx;
		return out;
	}
}
