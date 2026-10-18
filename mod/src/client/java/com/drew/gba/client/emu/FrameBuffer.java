package com.drew.gba.client.emu;

/**
 * Latest video frame handed from the emulator thread to the render thread. The picture has the size the core
 * produces: 240x160 (GBA) or 160x144 (Game Boy / Game Boy Color), see {@link #width()} / {@link #height()}.
 * Consumers that need a fixed canvas (the CRT stream and the in-world TV, which both use 240x160) call
 * {@link #copyLetterboxed}.
 */
public final class FrameBuffer {
	private final byte[] data = new byte[GbaNative.WIDTH * GbaNative.HEIGHT * 4];
	private volatile int seq;
	private int w = GbaNative.WIDTH, h = GbaNative.HEIGHT;
	private volatile boolean pausedOverlay;

	/** A green "PAUSED" is drawn over the 240x160 canvas of {@link #copyLetterboxed} (the TV in the world and the spectators' stream) while set. */
	public void setPausedOverlay(boolean on) {
		if (pausedOverlay != on) {
			pausedOverlay = on;
			seq++; // consumers redraw
		}
	}

	/** Emulator thread, while paused: the picture did not change but the overlay has to reach the TV / stream again. */
	public void touch() { seq++; }

	private static final String[][] FONT = {
		{"1111.", "1...1", "1...1", "1111.", "1....", "1....", "1...."},        // P
		{".111.", "1...1", "1...1", "11111", "1...1", "1...1", "1...1"},        // A
		{"1...1", "1...1", "1...1", "1...1", "1...1", "1...1", ".111."},        // U
		{".1111", "1....", "1....", ".111.", "....1", "....1", "1111."},        // S
		{"11111", "1....", "1....", "1111.", "1....", "1....", "11111"},        // E
		{"1111.", "1...1", "1...1", "1...1", "1...1", "1...1", "1111."},        // D
	};

	/** Draws "PAUSED" (green, 3x scale, on a dark plate) in the middle of a 240x160 RGBA canvas. */
	public static void drawPaused(byte[] c) {
		final int cw = GbaNative.WIDTH, ch = GbaNative.HEIGHT, sc = 3, gw = 5 * sc, gap = sc;
		int[] order = {0, 1, 2, 3, 4, 5};
		int textW = order.length * gw + (order.length - 1) * gap, textH = 7 * sc;
		int x0 = (cw - textW) / 2, y0 = (ch - textH) / 2, pad = 6;
		for (int y = y0 - pad; y < y0 + textH + pad; y++) {
			for (int x = x0 - pad; x < x0 + textW + pad; x++) {
				int i = (y * cw + x) * 4;
				c[i] = (byte) ((c[i] & 0xFF) / 4); c[i + 1] = (byte) ((c[i + 1] & 0xFF) / 4); c[i + 2] = (byte) ((c[i + 2] & 0xFF) / 4);
			}
		}
		for (int k = 0; k < order.length; k++) {
			String[] g = FONT[order[k]];
			for (int gy = 0; gy < 7; gy++) for (int gx = 0; gx < 5; gx++) {
				if (g[gy].charAt(gx) != '1') continue;
				for (int dy = 0; dy < sc; dy++) for (int dx = 0; dx < sc; dx++) {
					int i = ((y0 + gy * sc + dy) * cw + x0 + k * (gw + gap) + gx * sc + dx) * 4;
					c[i] = (byte) 0x3C; c[i + 1] = (byte) 0xE0; c[i + 2] = (byte) 0x5A; c[i + 3] = (byte) 255;
				}
			}
		}
	}

	/** Emulator thread: fill via the callback (a GBA sized picture), then publish. */
	public synchronized void publish(java.util.function.Consumer<byte[]> fill) {
		fill.accept(data);
		w = GbaNative.WIDTH;
		h = GbaNative.HEIGHT;
		seq++;
	}

	/** Emulator thread: publish the core's current frame at the core's own size. */
	public synchronized void publish(GbaCore core) {
		w = core.frameWidth();
		h = core.frameHeight();
		core.copyFramebuffer(data);
		seq++;
	}

	public int seq() {
		return seq;
	}

	/** Width of the latest picture in pixels (240 or 160). */
	public synchronized int width() { return w; }

	/** Height of the latest picture in pixels (160 or 144). */
	public synchronized int height() { return h; }

	/** Render thread: copy the latest frame (width() * height() * 4 RGBA bytes, rows packed) into dst, which must be at least that long. */
	public synchronized void copyTo(byte[] dst) {
		System.arraycopy(data, 0, dst, 0, w * h * 4);
	}

	/**
	 * Copy the latest frame into a 240x160 RGBA canvas {@code dst}: a GBA picture as is; a smaller Game Boy picture
	 * centred 1:1 with opaque black borders around it.
	 */
	public synchronized void copyLetterboxed(byte[] dst) {
		letterbox(data, w, h, dst);
		if (pausedOverlay) drawPaused(dst);
	}

	/** Puts a {@code w} x {@code h} RGBA picture centred 1:1 on the 240x160 canvas {@code dst} (black borders, alpha 255). */
	public static void letterbox(byte[] src, int w, int h, byte[] dst) {
		final int cw = GbaNative.WIDTH, ch = GbaNative.HEIGHT;
		if (w == cw && h == ch) {
			System.arraycopy(src, 0, dst, 0, cw * ch * 4);
			return;
		}
		for (int i = 0; i < cw * ch * 4; i += 4) {
			dst[i] = 0;
			dst[i + 1] = 0;
			dst[i + 2] = 0;
			dst[i + 3] = (byte) 255;
		}
		int ox = Math.max(0, (cw - w) / 2), oy = Math.max(0, (ch - h) / 2);
		int cols = Math.min(w, cw), rows = Math.min(h, ch);
		for (int y = 0; y < rows; y++) System.arraycopy(src, y * w * 4, dst, ((y + oy) * cw + ox) * 4, cols * 4);
	}
}
