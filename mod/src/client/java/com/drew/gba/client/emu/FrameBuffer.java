package com.drew.gba.client.emu;

/** Latest video frame handed from the emulator thread to the render thread. */
public final class FrameBuffer {
	private final byte[] data = new byte[GbaNative.WIDTH * GbaNative.HEIGHT * 4];
	private volatile int seq;

	/** Emulator thread: fill via the callback, then publish. */
	public synchronized void publish(java.util.function.Consumer<byte[]> fill) {
		fill.accept(data);
		seq++;
	}

	public int seq() {
		return seq;
	}

	/** Render thread: copy latest frame into dst. */
	public synchronized void copyTo(byte[] dst) {
		System.arraycopy(data, 0, dst, 0, data.length);
	}
}
