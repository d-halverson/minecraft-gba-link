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
