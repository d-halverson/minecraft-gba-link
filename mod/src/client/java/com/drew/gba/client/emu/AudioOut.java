package com.drew.gba.client.emu;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

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
	private static SourceDataLine shared;
	private static LineFeeder sharedFeeder;
	private static boolean tried;

	private volatile LineFeeder line;
	private byte[] bytes = new byte[0];
	volatile float volume = 1f;

	AudioOut() {}

	/** Attach to the shared line, opening it on first use. Call from the emulator thread (device calls can block). */
	void open() {
		line = sharedLine();
	}

	private static synchronized LineFeeder sharedLine() {
		if (tried) return sharedFeeder;
		tried = true;
		try {
			AudioFormat fmt = new AudioFormat(GbaNative.AUDIO_RATE, 16, 2, true, false);
			int bufBytes = GbaNative.AUDIO_RATE / 10 * 4; // 100 ms
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt, bufBytes);
			SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
			l.open(fmt, bufBytes);
			l.start();
			shared = l;
			sharedFeeder = LineFeeder.forLine(l, GbaNative.AUDIO_RATE);
			LineFeeder.startTicking(sharedFeeder);
		} catch (Exception | Error e) {
			shared = null; // run silently
			sharedFeeder = null;
		}
		return sharedFeeder;
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
		float v = Math.max(0f, Math.min(1f, volume));
		v = v * v; // perceptual curve, roughly matches Minecraft's slider feel
		for (int i = 0; i < frames * 2; i++) {
			int s = (int) (samples[i] * v);
			bytes[i * 2] = (byte) s;
			bytes[i * 2 + 1] = (byte) (s >> 8);
		}
		l.write(bytes, n); // fades in after a pause; drops what does not fit
	}

	/** Detach from the shared line (the line itself stays open, see class comment). */
	@Override
	public void close() {
		line = null;
	}
}
