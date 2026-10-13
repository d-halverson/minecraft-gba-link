package com.drew.gba.stream;

import java.util.Random;

/** Generators of GBA-like 240x160 RGBA frames (15-bit colour expanded to 8 bit, A=255). */
final class SyntheticScenes {
	static final int W = 240, H = 160;
	private SyntheticScenes() {}

	static int gba(int r5, int g5, int b5) { // 0xAABBGGRR as bytes r,g,b,a packed little endian
		int r = (r5 << 3) | (r5 >> 2), g = (g5 << 3) | (g5 >> 2), b = (b5 << 3) | (b5 >> 2);
		return r | (g << 8) | (b << 16) | (255 << 24);
	}

	static void put(byte[] f, int x, int y, int c) {
		if (x < 0 || y < 0 || x >= W || y >= H) return;
		int o = (y * W + x) * 4;
		f[o] = (byte) c;
		f[o + 1] = (byte) (c >> 8);
		f[o + 2] = (byte) (c >> 16);
		f[o + 3] = (byte) 255;
	}

	static int[] palette16(long seed) {
		Random r = new Random(seed);
		int[] p = new int[16];
		for (int i = 0; i < 16; i++) p[i] = gba(r.nextInt(32), r.nextInt(32), r.nextInt(32));
		return p;
	}

	/** A 64x64-tile world (512x512 px) of 8x8 tiles drawn from 24 distinct tile patterns. */
	static final class World {
		final int[][] tiles = new int[24][64];
		final int[] map = new int[64 * 64];
		final int[] pal;
		World(long seed) {
			Random r = new Random(seed);
			pal = palette16(seed);
			for (int[] t : tiles) {
				int base = r.nextInt(16), alt = r.nextInt(16);
				for (int i = 0; i < 64; i++) t[i] = r.nextInt(5) == 0 ? alt : base;
			}
			for (int i = 0; i < map.length; i++) map[i] = r.nextInt(3) == 0 ? r.nextInt(24) : r.nextInt(4);
		}
		int pixel(int wx, int wy) {
			wx &= 511; wy &= 511;
			return pal[tiles[map[(wy >> 3) * 64 + (wx >> 3)]][(wy & 7) * 8 + (wx & 7)]];
		}
	}

	/** Scrolling tile background, camera moves (vx,vy) px per encoded frame, plus nSprites 16x16 sprites. */
	static byte[] scroll(World w, int frame, int vx, int vy, int nSprites) {
		byte[] f = new byte[W * H * 4];
		int cx = frame * vx, cy = frame * vy;
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) put(f, x, y, w.pixel(x + cx, y + cy));
		for (int s = 0; s < nSprites; s++) sprite(f, 40 + s * 50 + (int) (20 * Math.sin((frame + s * 9) * 0.2)), 60 + (int) (30 * Math.cos((frame + s * 5) * 0.15)), s);
		return f;
	}

	static void sprite(byte[] f, int sx, int sy, int id) {
		int body = gba(31 - id * 5, 8 + id * 6, 4), edge = gba(2, 2, 2), eye = gba(31, 31, 31);
		for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
			int dx = x - 8, dy = y - 8, d = dx * dx + dy * dy;
			if (d > 60) continue;
			put(f, sx + x, sy + y, d > 44 ? edge : (x == 5 || x == 10) && y == 6 ? eye : body);
		}
	}

	/** Static field with a typing text box (8x8 pseudo-font glyphs revealed 1 char / 2 frames). */
	static byte[] textBox(World w, int frame) {
		byte[] f = new byte[W * H * 4];
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) put(f, x, y, w.pixel(x, y));
		int boxTop = 104, boxCol = gba(30, 30, 28), ink = gba(2, 2, 4), border = gba(4, 4, 20);
		for (int y = boxTop; y < H; y++) for (int x = 0; x < W; x++) {
			boolean edge = y < boxTop + 2 || y >= H - 2 || x < 2 || x >= W - 2;
			put(f, x, y, edge ? border : boxCol);
		}
		Random r = new Random(5);
		int chars = frame / 2;
		for (int c = 0; c < chars && c < 3 * 26; c++) {
			int gx = 12 + (c % 26) * 8, gy = boxTop + 10 + (c / 26) * 16;
			long g = r.nextLong() | 0x0042_1842_1842_1800L;
			for (int i = 0; i < 64; i++) if (((g >> i) & 1) != 0 && (i & 7) < 7 && (i >> 3) < 7) put(f, gx + (i & 7), gy + (i >> 3), ink);
		}
		return f;
	}

	/** Full-screen fade from the world to white over n frames (every pixel changes every frame). */
	static byte[] fade(World w, int frame, int n) {
		byte[] f = new byte[W * H * 4];
		int a = Math.min(31, frame * 31 / n);
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
			int c = w.pixel(x, y);
			int r = ((c & 255) >> 3), g = (((c >> 8) & 255) >> 3), b = (((c >> 16) & 255) >> 3);
			put(f, x, y, gba(r + (31 - r) * a / 31, g + (31 - g) * a / 31, b + (31 - b) * a / 31));
		}
		return f;
	}

	/** Alternating full-screen flashes. */
	static byte[] flash(World w, int frame) {
		if ((frame / 2) % 2 == 0) return scroll(w, 0, 0, 0, 0);
		byte[] f = new byte[W * H * 4];
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) put(f, x, y, gba(31, 31, 31));
		return f;
	}

	/** Worst case: every pixel a fresh random 15-bit colour. */
	static byte[] noise(Random r) {
		byte[] f = new byte[W * H * 4];
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) put(f, x, y, gba(r.nextInt(32), r.nextInt(32), r.nextInt(32)));
		return f;
	}
}
