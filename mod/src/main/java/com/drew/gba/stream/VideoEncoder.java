package com.drew.gba.stream;

import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * Compresses 240x160 RGBA frames into stream packets (see SPEC "Stream codec contract").
 *
 * <p>Lossless for GBA output (15-bit colour). Format, all little-endian:
 * <pre>
 * header (8 bytes): magic 0xC7, flags, frame u16, refFrame u16, dx i8, dy i8
 *   flags: 1 keyframe, 2 unchanged (header only), 4 colours expand as v&lt;&lt;3 (else v&lt;&lt;3|v&gt;&gt;2), 8 palette
 * payload (raw-deflate): [palette: count-1 u8, count x u16 BGR555]  (only with flag 8)
 *   keyframe: 38400 pixels in raster order
 *   delta:    600 tile modes (30x20 tiles of 8x8), then 8-byte masks of every patch tile, then pixels of every
 *             non-skip tile in tile order. Mode 0 skip (keep reference), 1 copy from reference shifted by
 *             (dx,dy), 2 raw 64 px, 3 reference + patch, 4 shifted reference + patch.
 *   pixel = palette index (1 byte) or BGR555 (u16)
 * </pre>
 * Delta frames are predicted from the previous <em>decoded</em> frame. A global scroll (dx,dy) up to +-8 px is
 * estimated each frame. If a delta packet would exceed {@link #MAX_DELTA_PACKET} (noise, ...), the encoder only
 * sends as many changed tiles as fit (rotating start so every tile is refreshed in turn) and the other tiles stay
 * stale for a few frames: a coarser temporal mode, still never above the cap.
 *
 * <p>Not thread safe: one encoder per stream.
 */
public final class VideoEncoder {
	static final int W = 240, H = 160, TW = 30, TH = 20, NT = TW * TH, NPIX = W * H;
	static final int MAGIC = 0xC7, HEADER = 8;
	static final int F_KEY = 1, F_SAME = 2, F_SHIFTEXP = 4, F_PAL = 8;
	static final int MAX_SHIFT = 8;
	/** Maximum size of a non-key packet; at 30 fps this is ~285 KB/s. */
	public static final int MAX_DELTA_PACKET = 9500;
	/** Frames between automatic keyframes (2 s at 30 fps). */
	public static final int KEYFRAME_INTERVAL = 60;
	static final int RAW_MAX = 1 + 512 + NT + NT * 8 + NPIX * 2;

	private static final byte[] EXP = new byte[256]; // bit0: v == q<<3|q>>2, bit1: v == q<<3 (q = v>>3)
	static {
		for (int v = 0; v < 256; v++) {
			int q = v >> 3;
			EXP[v] = (byte) ((v == ((q << 3) | (q >> 2)) ? 1 : 0) | (v == (q << 3) ? 2 : 0));
		}
	}

	private short[] ref = new short[NPIX], cur = new short[NPIX], newRef = new short[NPIX];
	private boolean haveRef, shiftExp;
	private int frame, lastFrame, sinceKey, rot;

	private final byte[] modeA = new byte[NT], modeB = new byte[NT], emit = new byte[NT];
	private final int[] costA = new int[NT], costB = new int[NT];
	private final byte[] raw = new byte[RAW_MAX];
	private final byte[] out = new byte[HEADER + RAW_MAX + 2048];
	private final short[] vals = new short[NPIX];
	private final byte[] masks = new byte[NT * 8];
	private int bestDx, bestDy;
	private final int[] stamp = new int[32768];
	private final byte[] palIdx = new byte[32768];
	private int stampGen;
	private final int[] sx = new int[512], sy = new int[512];
	private final Deflater deflater = new Deflater(1, true);

	public byte[] encode(byte[] rgba, boolean forceKeyframe) {
		if (rgba == null || rgba.length < NPIX * 4) throw new IllegalArgumentException("need 240x160 RGBA");
		quantise(rgba);
		boolean key = forceKeyframe || !haveRef || sinceKey >= KEYFRAME_INTERVAL;
		int fr = frame & 0xFFFF, refFrame = lastFrame;
		int flags = shiftExp ? F_SHIFTEXP : 0;
		byte[] pkt;
		if (key) {
			pkt = encodeKey(fr, flags);
			System.arraycopy(cur, 0, ref, 0, NPIX);
			sinceKey = 0;
			haveRef = true;
		} else {
			keyed = false;
			pkt = encodeDelta(fr, refFrame, flags);
			sinceKey = keyed ? 0 : sinceKey + 1;
		}
		lastFrame = fr;
		frame = (frame + 1) & 0xFFFF;
		return pkt;
	}

	private void quantise(byte[] p) {
		int ok = 3;
		short[] c = cur;
		for (int i = 0, j = 0; i < NPIX; i++, j += 4) {
			int r = p[j] & 255, g = p[j + 1] & 255, b = p[j + 2] & 255;
			ok &= EXP[r] & EXP[g] & EXP[b];
			c[i] = (short) ((r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10));
		}
		if (ok == 1) shiftExp = false;
		else if (ok == 2) shiftExp = true;
		else if (ok == 0) shiftExp = false; // not GBA-like input: 15-bit quantised, lossy
		// ok == 3 (e.g. all black): keep the previous mode
	}

	private int header(byte[] o, int flags, int fr, int refFrame, int dx, int dy) {
		o[0] = (byte) MAGIC;
		o[1] = (byte) flags;
		o[2] = (byte) fr;
		o[3] = (byte) (fr >> 8);
		o[4] = (byte) refFrame;
		o[5] = (byte) (refFrame >> 8);
		o[6] = (byte) dx;
		o[7] = (byte) dy;
		return HEADER;
	}

	// ------------------------------------------------------------------ keyframe

	private byte[] encodeKey(int fr, int flags) {
		System.arraycopy(cur, 0, vals, 0, NPIX);
		return finish(flags | F_KEY, fr, 0, 0, 0, null, 0, NPIX);
	}

	// ------------------------------------------------------------------ delta

	private byte[] encodeDelta(int fr, int refFrame, int flags) {
		int changed = plan(0, 0, modeA, costA);
		if (changed == 0) {
			byte[] o = new byte[HEADER];
			header(o, flags | F_SAME, fr, refFrame, 0, 0);
			return o;
		}
		byte[] mode = modeA;
		int[] cost = costA;
		int dx = 0, dy = 0;
		if (changed >= 36 && findShift()) {
			plan(bestDx, bestDy, modeB, costB);
			if (total(costB) < total(costA)) {
				mode = modeB;
				cost = costB;
				dx = bestDx;
				dy = bestDy;
			}
		}
		int est = total(cost);
		// First try everything (the cost estimate is far too pessimistic for compressible content), unless the
		// changed pixels are so colour-rich (noise, photos) that they cannot possibly fit: then start at the cap.
		boolean noisy = est > 4 * MAX_DELTA_PACKET && richColours(mode, 2500);
		int budget = noisy ? MAX_DELTA_PACKET * 95 / 100 : Integer.MAX_VALUE;
		byte[] pkt;
		for (int attempt = 0; ; attempt++) {
			pkt = buildDelta(mode, cost, budget, fr, refFrame, flags, dx, dy);
			if (pkt.length <= MAX_DELTA_PACKET) break;
			if (attempt == 0 && !noisy && pkt.length < 4 * MAX_DELTA_PACKET) { // a scene cut: a lossless keyframe may still fit under the cap
				byte[] k = encodeKey(fr, flags);
				if (k.length <= MAX_DELTA_PACKET) {
					System.arraycopy(cur, 0, newRef, 0, NPIX);
					keyed = true;
					pkt = k;
					break;
				}
			}
			if (attempt >= 7) {
				budget = 0; // cannot happen in practice; send an empty (all-skip) delta
				pkt = buildDelta(mode, cost, budget, fr, refFrame, flags, dx, dy);
				break;
			}
			int base = budget == Integer.MAX_VALUE ? est : budget;
			budget = (int) ((long) base * (MAX_DELTA_PACKET - 300) / pkt.length * 9 / 10);
		}
		short[] t = ref;
		ref = newRef;
		newRef = t;
		return pkt;
	}

	private boolean keyed;

	/** True if the pixels of the non-skip tiles hold more than {@code limit} distinct colours. */
	private boolean richColours(byte[] mode, int limit) {
		int gen = ++stampGen, nd = 0;
		for (int t = 0; t < NT; t++) {
			if (mode[t] == 0) continue;
			int x0 = (t % TW) * 8, y0 = (t / TW) * 8;
			for (int y = 0; y < 8; y++) {
				int o = (y0 + y) * W + x0;
				for (int x = 0; x < 8; x++) {
					int c = cur[o + x];
					if (stamp[c] != gen) {
						stamp[c] = gen;
						if (++nd > limit) return true;
					}
				}
			}
		}
		return false;
	}

	private static int total(int[] c) {
		int s = 0;
		for (int i = 0; i < NT; i++) s += c[i];
		return s;
	}

	/**
	 * Classify every tile against the reference, optionally shifted by (dx,dy): content moved so that
	 * cur(x,y) ~ ref(x-dx, y-dy). Returns the number of tiles that differ from the unshifted reference.
	 */
	private int plan(int dx, int dy, byte[] mode, int[] cost) {
		int changedTiles = 0;
		final short[] cu = cur, rf = ref;
		final boolean shifted = dx != 0 || dy != 0;
		for (int ty = 0; ty < TH; ty++) {
			for (int tx = 0; tx < TW; tx++) {
				int t = ty * TW + tx, x0 = tx * 8, y0 = ty * 8;
				int nS = 0, nP = 0;
				for (int y = 0; y < 8; y++) {
					int o = (y0 + y) * W + x0;
					for (int x = 0; x < 8; x++) if (cu[o + x] != rf[o + x]) nS++;
				}
				if (nS == 0) {
					mode[t] = 0;
					cost[t] = 0;
					continue;
				}
				changedTiles++;
				if (shifted) {
					for (int y = 0; y < 8; y++) {
						int yy = y0 + y, sy = yy - dy;
						for (int x = 0; x < 8; x++) {
							int xx = x0 + x, sx = xx - dx;
							if (sy < 0 || sy >= H || sx < 0 || sx >= W || cu[yy * W + xx] != rf[sy * W + sx]) nP++;
						}
					}
					if (nP == 0) {
						mode[t] = 1;
						cost[t] = 1;
						continue;
					}
				}
				boolean useShift = shifted && nP < nS;
				int n = useShift ? nP : nS;
				if (n <= 40) {
					mode[t] = (byte) (useShift ? 4 : 3);
					cost[t] = 1 + 8 + 2 * n;
				} else {
					mode[t] = 2;
					cost[t] = 1 + 128;
				}
			}
		}
		return changedTiles;
	}

	/** Look for a global scroll by testing edge pixels of changed tiles against shifted reference. */
	private boolean findShift() {
		int ns = 0;
		final short[] cu = cur, rf = ref;
		for (int t = 0; t < NT && ns < 500; t++) {
			if (modeA[t] == 0) continue;
			int x0 = (t % TW) * 8, y0 = (t / TW) * 8, got = 0;
			for (int k = 0; k < 64 && got < 2; k++) {
				int p = (k * 37) & 63; // scattered scan order
				int x = x0 + (p & 7), y = y0 + (p >> 3);
				if (x >= W - 1 || y >= H - 1) continue;
				short c = cu[y * W + x];
				if (c == cu[y * W + x + 1] || c == cu[(y + 1) * W + x]) continue;
				sx[ns] = x;
				sy[ns] = y;
				ns++;
				got++;
			}
		}
		if (ns < 16) return false;
		int s0 = 0;
		for (int i = 0; i < ns; i++) if (cu[sy[i] * W + sx[i]] == rf[sy[i] * W + sx[i]]) s0++;
		int best = Math.max(s0 + ns / 20, ns / 4), bdx = 0, bdy = 0;
		for (int dy = -MAX_SHIFT; dy <= MAX_SHIFT; dy++) {
			for (int dx = -MAX_SHIFT; dx <= MAX_SHIFT; dx++) {
				if (dx == 0 && dy == 0) continue;
				int s = 0;
				for (int i = 0; i < ns; i++) {
					int x = sx[i] - dx, y = sy[i] - dy;
					if (x >= 0 && x < W && y >= 0 && y < H && rf[y * W + x] == cu[sy[i] * W + sx[i]]) s++;
				}
				if (s > best) {
					best = s;
					bdx = dx;
					bdy = dy;
				}
			}
		}
		if (bdx == 0 && bdy == 0) return false;
		bestDx = bdx;
		bestDy = bdy;
		return true;
	}

	private byte[] buildDelta(byte[] mode, int[] cost, int budget, int fr, int refFrame, int flags, int dx, int dy) {
		System.arraycopy(mode, 0, emit, 0, NT);
		if (budget != Integer.MAX_VALUE) {
			int acc = 0;
			int start = rot;
			rot = (rot + 97) % NT;
			for (int i = 0; i < NT; i++) {
				int t = (start + i) % NT;
				if (emit[t] == 0) continue;
				if (acc + cost[t] > budget) emit[t] = 0;
				else acc += cost[t];
			}
		}
		System.arraycopy(ref, 0, newRef, 0, NPIX);
		int nv = 0, nm = 0;
		final short[] cu = cur, rf = ref;
		for (int t = 0; t < NT; t++) {
			int m = emit[t];
			if (m == 0) continue;
			int x0 = (t % TW) * 8, y0 = (t / TW) * 8;
			boolean shifted = m == 1 || m == 4;
			for (int y = 0; y < 8; y++) {
				int o = (y0 + y) * W + x0;
				for (int x = 0; x < 8; x++) newRef[o + x] = cu[o + x];
			}
			if (m == 1) continue;
			if (m == 2) {
				for (int y = 0; y < 8; y++) {
					int o = (y0 + y) * W + x0;
					for (int x = 0; x < 8; x++) vals[nv++] = cu[o + x];
				}
				continue;
			}
			for (int y = 0; y < 8; y++) {
				int mask = 0;
				for (int x = 0; x < 8; x++) {
					int xx = x0 + x, yy = y0 + y;
					short c = cu[yy * W + xx], b;
					if (shifted) {
						int sxx = xx - dx, syy = yy - dy;
						b = (sxx < 0 || sxx >= W || syy < 0 || syy >= H) ? (short) -1 : rf[syy * W + sxx];
					} else b = rf[yy * W + xx];
					if (b != c) {
						mask |= 1 << x;
						vals[nv++] = c;
					}
				}
				masks[nm++] = (byte) mask;
			}
		}
		return finish(flags, fr, refFrame, dx, dy, emit, nm, nv);
	}

	/** Assemble [palette][modes][masks][pixels], deflate, prepend the header. */
	private byte[] finish(int flags, int fr, int refFrame, int dx, int dy, byte[] modes, int nm, int nv) {
		// palette over the pixels actually sent
		int gen = ++stampGen;
		int nd = 0;
		boolean pal = true;
		short[] pcol = palette;
		for (int i = 0; i < nv; i++) {
			int c = vals[i] & 0x7FFF;
			if (stamp[c] != gen) {
				stamp[c] = gen;
				if (nd == 256) {
					pal = false;
					break;
				}
				palIdx[c] = (byte) nd;
				pcol[nd++] = (short) c;
			}
		}
		int p = 0;
		final byte[] r = raw;
		if (pal && nv > 0) {
			flags |= F_PAL;
			r[p++] = (byte) (nd - 1);
			for (int i = 0; i < nd; i++) {
				r[p++] = (byte) pcol[i];
				r[p++] = (byte) (pcol[i] >> 8);
			}
		} else pal = false;
		if (modes != null) {
			System.arraycopy(modes, 0, r, p, NT);
			p += NT;
			System.arraycopy(masks, 0, r, p, nm);
			p += nm;
		}
		if (pal) {
			for (int i = 0; i < nv; i++) r[p++] = palIdx[vals[i] & 0x7FFF];
		} else {
			for (int i = 0; i < nv; i++) {
				r[p++] = (byte) vals[i];
				r[p++] = (byte) (vals[i] >> 8);
			}
		}
		header(out, flags, fr, refFrame, dx, dy);
		deflater.reset();
		deflater.setInput(r, 0, p);
		deflater.finish();
		int n = HEADER;
		while (!deflater.finished()) {
			int k = deflater.deflate(out, n, out.length - n);
			n += k;
			if (k == 0 && !deflater.finished()) break; // cannot happen with a large enough buffer
		}
		return Arrays.copyOf(out, n);
	}

	private final short[] palette = new short[256];
}
