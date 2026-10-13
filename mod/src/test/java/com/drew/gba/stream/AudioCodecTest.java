package com.drew.gba.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AudioCodecTest {
	static short[] sine(int frames, double hz, double amp, int offset) {
		short[] s = new short[frames * 2];
		for (int i = 0; i < frames; i++) {
			short v = (short) Math.round(amp * Math.sin(2 * Math.PI * hz * (i + offset) / 32768.0));
			s[2 * i] = v;
			s[2 * i + 1] = v;
		}
		return s;
	}

	/** Encode a sine in odd-sized chunks; returns the decoded mono stream. */
	static short[] roundTrip(double hz, double amp, int totalFrames, List<byte[]> packetsOut) {
		AudioEncoder enc = new AudioEncoder();
		AudioDecoder dec = new AudioDecoder();
		int[] chunk = {1092, 1091, 1093, 500, 2000, 1, 1092};
		short[] out = new short[totalFrames];
		short[] tmp = new short[8192];
		int sent = 0, n = 0, c = 0;
		while (sent < totalFrames) {
			int fr = Math.min(chunk[c++ % chunk.length], totalFrames - sent);
			byte[] p = enc.encode(sine(fr, hz, amp, sent), fr);
			if (packetsOut != null) packetsOut.add(p);
			sent += fr;
			int k = dec.decode(p, tmp);
			System.arraycopy(tmp, 0, out, n, k);
			n += k;
		}
		return Arrays.copyOf(out, n);
	}

	/** SNR (dB) of decoded vs ideal sine, searching a small delay (resampler group delay) and gain. */
	static double snr(short[] dec, double hz, double amp) {
		double best = -1e9;
		for (double delay = -2; delay <= 2; delay += 0.05) { // in 16 kHz samples
			double sig = 0, err = 0;
			for (int i = 400; i < dec.length - 400; i++) {
				double ideal = amp * Math.sin(2 * Math.PI * hz * (i + delay) / 16000.0);
				sig += ideal * ideal;
				err += (dec[i] - ideal) * (dec[i] - ideal);
			}
			best = Math.max(best, 10 * Math.log10(sig / err));
		}
		return best;
	}

	@Test
	void sineRoundTripSnr() {
		for (double hz : new double[] {440, 1000, 3000}) {
			short[] d = roundTrip(hz, 12000, 32768 * 2, null);
			assertEquals(16000 * 2, d.length, 40);
			double s = snr(d, hz, 12000);
			System.out.printf("audio SNR %.0f Hz: %.1f dB%n", hz, s);
			assertTrue(s > (hz > 2000 ? 15 : 27), hz + " Hz SNR " + s); // 4-bit ADPCM: noise grows with frequency
		}
	}

	@Test
	void bitrate() {
		List<byte[]> pk = new ArrayList<>();
		roundTrip(1000, 8000, 32768 * 2, pk);
		long bytes = pk.stream().mapToInt(p -> p.length).sum();
		assertEquals(8000.0, bytes / 2.0, 800);
	}

	@Test
	void aliasingIsSuppressed() { // 12 kHz would fold to 4 kHz at 16 kHz output
		short[] d = roundTrip(12000, 12000, 32768, null);
		double e = 0;
		for (int i = 200; i < d.length - 200; i++) e += (double) d[i] * d[i];
		double rms = Math.sqrt(e / (d.length - 400));
		assertTrue(rms < 12000 / Math.sqrt(2) / 20, "alias rms " + rms); // > 26 dB down
	}

	@Test
	void silence() {
		AudioEncoder enc = new AudioEncoder();
		AudioDecoder dec = new AudioDecoder();
		short[] tmp = new short[4096];
		for (int i = 0; i < 20; i++) {
			int k = dec.decode(enc.encode(new short[2184], 1092), tmp);
			for (int j = 0; j < k; j++) assertTrue(Math.abs(tmp[j]) <= 1, "" + tmp[j]);
			assertTrue(k >= 500 && k <= 540, "k=" + k);
		}
		assertEquals(0, dec.decode(enc.encode(new short[0], 0), tmp) >= 0 ? 0 : 1);
	}

	@Test
	void packetsAreIndependent() {
		List<byte[]> pk = new ArrayList<>();
		short[] full = roundTrip(1000, 12000, 32768 * 2, pk);
		AudioDecoder dec = new AudioDecoder();
		short[] a = new short[8192], b = new short[8192];
		int start = 0;
		for (int i = 0; i < 20; i++) start += dec.decode(pk.get(i), a);
		// a fresh decoder that never saw packets 0..19 decodes packet 20 identically
		int k = new AudioDecoder().decode(pk.get(20), a);
		int k2 = dec.decode(pk.get(20), b);
		assertEquals(k2, k);
		assertArrayEquals(Arrays.copyOf(b, k2), Arrays.copyOf(a, k));
		for (int j = 0; j < k; j++) assertEquals(full[start + j], a[j]);
		// and a lost packet only causes a local glitch: skipping 21..22 still yields a clean sine afterwards
		short[] tail = new short[8192];
		int kt = new AudioDecoder().decode(pk.get(30), tail);
		double maxAbs = 0;
		for (int j = 100; j < kt; j++) maxAbs = Math.max(maxAbs, Math.abs(tail[j]));
		assertTrue(maxAbs > 9000 && maxAbs < 15000);
	}

	@Test
	void decoderNeverThrows() {
		AudioDecoder dec = new AudioDecoder();
		short[] out = new short[100], big = new short[8192];
		Random r = new Random(1);
		AudioEncoder enc = new AudioEncoder();
		byte[] good = enc.encode(sine(1092, 440, 9000, 0), 1092);
		for (int i = 0; i < 5000; i++) {
			byte[] p = good.clone();
			switch (r.nextInt(3)) {
				case 0 -> p[r.nextInt(p.length)] = (byte) r.nextInt();
				case 1 -> p = Arrays.copyOf(p, r.nextInt(p.length));
				default -> { p = new byte[r.nextInt(50)]; r.nextBytes(p); }
			}
			dec.decode(p, big);
			dec.decode(p, out);
		}
		assertEquals(0, dec.decode(null, big));
		assertEquals(0, dec.decode(good, null));
		assertTrue(dec.decode(good, out) <= 100); // small output buffer: partial, no exception
	}
}
