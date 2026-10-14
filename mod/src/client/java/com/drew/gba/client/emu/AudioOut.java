package com.drew.gba.client.emu;


/**
 * 32768 Hz 16-bit stereo output with a ~100 ms buffer. Never blocks the emulator thread on a full buffer.
 *
 * All sessions share ONE JavaSound line that is opened once and then kept open for the life of the JVM.
 * Stopping/closing a line is not safe on macOS: {@code DirectAudioDevice.nStop} was observed to block forever
 * while holding the mixer lock, which then froze every later {@code open()} (the next game session would
 * never produce a frame). A session therefore only stops writing when it ends.
 *
 * A line nobody feeds underruns and (on Windows especially) replays stale buffer contents, which sounds like flickers
 * while paused. {@link LineFeeder} therefore fades the audio out and keeps the open line filled with silence whenever
 * no session is writing, and fades back in (about 10 ms) when one resumes.
 */
final class AudioOut implements AutoCloseable {

	private volatile LineFeeder line;
	private boolean counted;
	private byte[] bytes = new byte[0];

	AudioOut() {}

	/** Attach to the shared line, opening it on first use. Call from the emulator thread (device calls can block). */
	/** Number of open emulator sessions' outputs: while > 0 the CRT TVs stay quiet (see {@link AudioLines}). */
	private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();

	public static boolean gameActive() { return ACTIVE.get() > 0; }

	void open() {
		line = sharedLine();
		if (line != null && !counted) { counted = true; ACTIVE.incrementAndGet(); }
	}

	private static LineFeeder sharedLine() {
		AudioLines.open();
		return AudioLines.feeder();
	}

	boolean available() {
		return line != null;
	}

	/** Queue interleaved stereo samples; excess beyond the free buffer space is dropped. */
	void write(short[] samples, int frames) {
		LineFeeder l = line;
		if (l == null || frames <= 0) return;
		int n = frames * 4;
		if (bytes.length < n) bytes = new byte[n];
		for (int i = 0; i < frames * 2; i++) { // full-scale samples; the player's GBA volume is applied inside the LineFeeder
			int s = samples[i];
			bytes[i * 2] = (byte) s;
			bytes[i * 2 + 1] = (byte) (s >> 8);
		}
		l.write(bytes, n); // volume, fade-in after a pause; drops what does not fit
	}

	/** Detach from the shared line (the line itself stays open, see class comment). */
	@Override
	public void close() {
		if (counted) { counted = false; ACTIVE.decrementAndGet(); }
		line = null;
	}
}
