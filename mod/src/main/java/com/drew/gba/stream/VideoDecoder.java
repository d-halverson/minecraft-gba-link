package com.drew.gba.stream;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static com.drew.gba.stream.VideoEncoder.*;

/**
 * Decodes {@link VideoEncoder} packets back into 240x160 RGBA frames (alpha 255). Keeps the previous frame as
 * reference; one decoder per viewer stream, not thread safe. Never throws on bad input: it returns false and
 * {@link #needsKeyframe()} becomes true until the next keyframe decodes.
 */
public final class VideoDecoder {
	private short[] ref = new short[NPIX], next = new short[NPIX];
	private boolean haveFrame;
	private boolean needKey = true;
	private int lastFrame;
	private final byte[] raw = new byte[RAW_MAX + 64];
	private final Inflater inflater = new Inflater(true);
	private final short[] pcol = new short[256];

	/** @return false if the packet can't be decoded (e.g. missing keyframe); the caller requests a keyframe */
	public boolean decode(byte[] packet, byte[] rgbaOut) {
		try {
			if (packet == null || rgbaOut == null || rgbaOut.length < NPIX * 4) return false;
			if (packet.length < HEADER || (packet[0] & 255) != MAGIC) return fail();
			int flags = packet[1] & 255;
			int fr = (packet[2] & 255) | ((packet[3] & 255) << 8);
			int rf = (packet[4] & 255) | ((packet[5] & 255) << 8);
			int dx = packet[6], dy = packet[7];
			boolean key = (flags & F_KEY) != 0;
			if (!key && (!haveFrame || rf != lastFrame)) return fail();
			if ((flags & F_SAME) != 0) {
				if (key || packet.length != HEADER) return fail();
				System.arraycopy(ref, 0, next, 0, NPIX);
			} else if (!parse(packet, flags, dx, dy, key)) return fail();
			short[] t = ref;
			ref = next;
			next = t;
			lastFrame = fr;
			haveFrame = true;
			needKey = false;
			expand(rgbaOut, (flags & F_SHIFTEXP) != 0);
			return true;
		} catch (RuntimeException | DataFormatException e) {
			return fail();
		}
	}

	private boolean fail() {
		needKey = true;
		return false;
	}

	public boolean needsKeyframe() {
		return needKey;
	}

	private boolean parse(byte[] pkt, int flags, int dx, int dy, boolean key) throws DataFormatException {
		inflater.reset();
		inflater.setInput(pkt, HEADER, pkt.length - HEADER);
		int n = 0;
		while (n < raw.length) {
			int k = inflater.inflate(raw, n, raw.length - n);
			if (k == 0) break;
			n += k;
		}
		if (!inflater.finished() || inflater.getRemaining() != 0) return false;
		int p = 0, palN = 0;
		boolean pal = (flags & F_PAL) != 0;
		if (pal) {
			if (n < 1) return false;
			palN = (raw[p++] & 255) + 1;
			if (p + palN * 2 > n) return false;
			for (int i = 0; i < palN; i++, p += 2) {
				int c = (raw[p] & 255) | ((raw[p + 1] & 255) << 8);
				if (c >= 0x8000) return false;
				pcol[i] = (short) c;
			}
		}
		final int bpp = pal ? 1 : 2;
		if (key) {
			if (n - p != NPIX * bpp) return false;
			for (int i = 0; i < NPIX; i++) {
				int c = pix(p, pal, palN);
				if (c < 0) return false;
				next[i] = (short) c;
				p += bpp;
			}
			return true;
		}
		if (n - p < NT) return false;
		int mp = p;
		p += NT;
		// masks follow the modes, pixels follow the masks: first pass sums the mask section
		int maskStart = p, nm = 0;
		for (int t = 0; t < NT; t++) {
			int m = raw[mp + t];
			if (m < 0 || m > 4) return false;
			if (m >= 3) nm += 8;
		}
		p += nm;
		if (p > n) return false;
		System.arraycopy(ref, 0, next, 0, NPIX);
		int mk = maskStart;
		for (int t = 0; t < NT; t++) {
			int m = raw[mp + t];
			if (m == 0) continue;
			int x0 = (t % TW) * 8, y0 = (t / TW) * 8;
			boolean shifted = m == 1 || m == 4;
			for (int y = 0; y < 8; y++) {
				int yy = y0 + y;
				int mask = 0;
				if (m >= 3) mask = raw[mk++] & 255;
				for (int x = 0; x < 8; x++) {
					int xx = x0 + x, c;
					if (m == 2 || (mask & (1 << x)) != 0) {
						if (p + bpp > n) return false;
						c = pix(p, pal, palN);
						if (c < 0) return false;
						p += bpp;
					} else if (shifted) {
						int sx = xx - dx, sy = yy - dy;
						if (sx < 0 || sx >= W || sy < 0 || sy >= H) return false;
						c = ref[sy * W + sx];
					} else c = ref[yy * W + xx];
					next[yy * W + xx] = (short) c;
				}
			}
		}
		return p == n;
	}

	private int pix(int p, boolean pal, int palN) {
		if (pal) {
			int i = raw[p] & 255;
			return i < palN ? pcol[i] : -1;
		}
		int c = (raw[p] & 255) | ((raw[p + 1] & 255) << 8);
		return c < 0x8000 ? c : -1;
	}

	private void expand(byte[] out, boolean shiftOnly) {
		byte[] lut = new byte[32];
		for (int v = 0; v < 32; v++) lut[v] = (byte) (shiftOnly ? v << 3 : (v << 3) | (v >> 2));
		final short[] r = ref;
		for (int i = 0, j = 0; i < NPIX; i++, j += 4) {
			int c = r[i];
			out[j] = lut[c & 31];
			out[j + 1] = lut[(c >> 5) & 31];
			out[j + 2] = lut[(c >> 10) & 31];
			out[j + 3] = (byte) 255;
		}
	}
}
