package com.drew.gba.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VideoCodecTest {
	static final int N = 240 * 160 * 4;

	/** Round trips a sequence, returns the packets. Asserts bit-exact output on every frame. */
	static List<byte[]> roundTrip(List<byte[]> frames, boolean exact) { return roundTrip(frames, exact, false); }

	/** settle: a scene cut larger than the packet cap may take a few frames to converge (stale tiles). */
	static List<byte[]> roundTrip(List<byte[]> frames, boolean exact, boolean settle) {
		VideoEncoder enc = new VideoEncoder();
		VideoDecoder dec = new VideoDecoder();
		byte[] out = new byte[N];
		List<byte[]> pkts = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			byte[] p = enc.encode(frames.get(i), false);
			pkts.add(p);
			assertTrue(dec.decode(p, out), "frame " + i);
			assertFalse(dec.needsKeyframe());
			if (exact && settle) {
				int extra = 0;
				while (!java.util.Arrays.equals(frames.get(i), out)) {
					assertTrue(++extra <= 12, "no convergence at frame " + i);
					assertTrue(dec.decode(enc.encode(frames.get(i), false), out));
				}
			} else if (exact) assertArrayEquals(frames.get(i), out, "frame " + i);
		}
		return pkts;
	}

	@Test
	void scenesRoundTripExactly() {
		SyntheticScenes.World w = new SyntheticScenes.World(1);
		List<byte[]> fr = new ArrayList<>();
		for (int i = 0; i < 40; i++) fr.add(SyntheticScenes.scroll(w, i, 1, 0, 3));
		for (int i = 0; i < 40; i++) fr.add(SyntheticScenes.scroll(w, i, -2, 1, 2));
		for (int i = 0; i < 40; i++) fr.add(SyntheticScenes.scroll(w, i, 0, 2, 0));
		for (int i = 0; i < 80; i++) fr.add(SyntheticScenes.textBox(w, i));
		for (int i = 0; i < 40; i++) fr.add(SyntheticScenes.fade(w, i, 30));
		for (int i = 0; i < 20; i++) fr.add(SyntheticScenes.flash(w, i));
		Random r = new Random(3);
		for (int i = 0; i < 5; i++) fr.add(SyntheticScenes.noise(r));
		roundTrip(fr, true, true);
	}

	@Test
	void eachSceneIsStrictlyExact() {
		SyntheticScenes.World w = new SyntheticScenes.World(21);
		for (int scene = 0; scene < 5; scene++) {
			List<byte[]> fr = new ArrayList<>();
			for (int i = 0; i < 40; i++) {
				fr.add(switch (scene) {
					case 0 -> SyntheticScenes.scroll(w, i, 1, 1, 3);
					case 1 -> SyntheticScenes.textBox(w, i);
					case 2 -> SyntheticScenes.fade(w, i, 30);
					case 3 -> SyntheticScenes.flash(w, i);
					default -> SyntheticScenes.scroll(w, i, -2, 0, 1);
				});
			}
			roundTrip(fr, true);
		}
	}

	@Test
	void staticFrameIsTiny() {
		SyntheticScenes.World w = new SyntheticScenes.World(2);
		byte[] f = SyntheticScenes.scroll(w, 0, 0, 0, 0);
		VideoEncoder enc = new VideoEncoder();
		enc.encode(f, false);
		assertTrue(enc.encode(f, false).length <= 8);
	}

	@Test
	void scrollIsMotionCompensated() {
		SyntheticScenes.World w = new SyntheticScenes.World(4);
		List<byte[]> fr = new ArrayList<>();
		for (int i = 0; i < 30; i++) fr.add(SyntheticScenes.scroll(w, i, 2, 1, 0));
		List<byte[]> p = roundTrip(fr, true);
		for (int i = 1; i < p.size(); i++) assertTrue(p.get(i).length < 2500, "frame " + i + " " + p.get(i).length);
		assertTrue(p.get(5)[6] != 0 || p.get(5)[7] != 0, "shift used");
	}

	@Test
	void noiseIsCapped() {
		Random r = new Random(9);
		VideoEncoder enc = new VideoEncoder();
		VideoDecoder dec = new VideoDecoder();
		byte[] out = new byte[N];
		enc.encode(SyntheticScenes.noise(r), false);
		for (int i = 0; i < 20; i++) {
			byte[] p = enc.encode(SyntheticScenes.noise(r), false);
			assertTrue(p.length <= VideoEncoder.MAX_DELTA_PACKET, "" + p.length);
		}
		// whole stream still decodes (stale tiles only)
		enc = new VideoEncoder();
		for (int i = 0; i < 70; i++) assertTrue(dec.decode(enc.encode(SyntheticScenes.noise(r), false), out));
	}

	@Test
	void directColourWhenOver256Colours() {
		byte[] f = SyntheticScenes.noise(new Random(1));
		VideoEncoder enc = new VideoEncoder();
		VideoDecoder dec = new VideoDecoder();
		byte[] out = new byte[N];
		assertTrue(dec.decode(enc.encode(f, true), out));
		assertArrayEquals(f, out);
	}

	@Test
	void forcedKeyframeAndPeriodicKeyframe() {
		SyntheticScenes.World w = new SyntheticScenes.World(5);
		VideoEncoder enc = new VideoEncoder();
		byte[] f = SyntheticScenes.scroll(w, 0, 0, 0, 1);
		assertEquals(1, enc.encode(f, false)[1] & 1);
		assertEquals(0, enc.encode(f, false)[1] & 1);
		assertEquals(1, enc.encode(f, true)[1] & 1);
		int keys = 0;
		for (int i = 0; i < 130; i++) keys += enc.encode(SyntheticScenes.scroll(w, i, 1, 0, 1), false)[1] & 1;
		assertTrue(keys >= 2, "keys " + keys);
	}

	@Test
	void missingReferenceNeedsKeyframe() {
		SyntheticScenes.World w = new SyntheticScenes.World(6);
		VideoEncoder enc = new VideoEncoder();
		VideoDecoder dec = new VideoDecoder();
		byte[] out = new byte[N];
		assertTrue(dec.needsKeyframe());
		byte[] k = enc.encode(SyntheticScenes.scroll(w, 0, 1, 0, 1), false);
		byte[] d1 = enc.encode(SyntheticScenes.scroll(w, 1, 1, 0, 1), false);
		byte[] d2 = enc.encode(SyntheticScenes.scroll(w, 2, 1, 0, 1), false);
		assertFalse(dec.decode(d1, out)); // no keyframe yet
		assertTrue(dec.needsKeyframe());
		assertTrue(dec.decode(k, out));
		assertFalse(dec.needsKeyframe());
		assertFalse(dec.decode(d2, out)); // d1 was lost
		assertTrue(dec.needsKeyframe());
		assertTrue(dec.decode(enc.encode(SyntheticScenes.scroll(w, 3, 1, 0, 1), true), out));
		assertFalse(dec.needsKeyframe());
	}

	@Test
	void fuzzNeverThrows() {
		SyntheticScenes.World w = new SyntheticScenes.World(7);
		VideoEncoder enc = new VideoEncoder();
		List<byte[]> pk = new ArrayList<>();
		for (int i = 0; i < 6; i++) pk.add(enc.encode(SyntheticScenes.scroll(w, i, 2, 1, 2), false));
		pk.add(enc.encode(SyntheticScenes.noise(new Random(2)), false));
		Random r = new Random(11);
		byte[] out = new byte[N];
		for (int iter = 0; iter < 4000; iter++) {
			VideoDecoder dec = new VideoDecoder();
			int upto = r.nextInt(pk.size());
			for (int i = 0; i < upto; i++) dec.decode(pk.get(i), out);
			byte[] p = pk.get(upto).clone();
			switch (r.nextInt(4)) {
				case 0 -> { for (int k = r.nextInt(6) + 1; k > 0; k--) p[r.nextInt(p.length)] ^= (byte) (1 << r.nextInt(8)); }
				case 1 -> p = java.util.Arrays.copyOf(p, r.nextInt(p.length + 1));
				case 2 -> { for (int k = HEADER_RANDOM; k < p.length; k++) p[k] = (byte) r.nextInt(); }
				default -> { p = new byte[r.nextInt(300)]; r.nextBytes(p); if (p.length > 0 && r.nextBoolean()) p[0] = (byte) 0xC7; }
			}
			dec.decode(p, out);
			dec.decode(p, new byte[10]);
		}
		VideoDecoder dec = new VideoDecoder();
		assertFalse(dec.decode(null, out));
		assertFalse(dec.decode(new byte[0], out));
		assertTrue(dec.needsKeyframe());
	}
	private static final int HEADER_RANDOM = 8;

	@Test
	void corruptDeltaLeavesNeedKeyframe() {
		SyntheticScenes.World w = new SyntheticScenes.World(8);
		VideoEncoder enc = new VideoEncoder();
		VideoDecoder dec = new VideoDecoder();
		byte[] out = new byte[N];
		dec.decode(enc.encode(SyntheticScenes.scroll(w, 0, 1, 0, 1), false), out);
		byte[] d = enc.encode(SyntheticScenes.scroll(w, 1, 1, 0, 1), false);
		d = java.util.Arrays.copyOf(d, d.length - 3);
		assertFalse(dec.decode(d, out));
		assertTrue(dec.needsKeyframe());
	}
}
