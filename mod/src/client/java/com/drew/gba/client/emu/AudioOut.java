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
 */
final class AudioOut implements AutoCloseable {
	private static SourceDataLine shared;
	private static boolean tried;

	private volatile SourceDataLine line;
	private byte[] bytes = new byte[0];
	volatile float volume = 1f;

	AudioOut() {}

	/** Attach to the shared line, opening it on first use. Call from the emulator thread (device calls can block). */
	void open() {
		line = sharedLine();
	}

	private static synchronized SourceDataLine sharedLine() {
		if (tried) return shared;
		tried = true;
		try {
			AudioFormat fmt = new AudioFormat(GbaNative.AUDIO_RATE, 16, 2, true, false);
			int bufBytes = GbaNative.AUDIO_RATE / 10 * 4; // 100 ms
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt, bufBytes);
			SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
			l.open(fmt, bufBytes);
			l.start();
			shared = l;
		} catch (Exception | Error e) {
			shared = null; // run silently
		}
		return shared;
	}

	boolean available() {
		return line != null;
	}

	/** Queue interleaved stereo samples; excess beyond the free buffer space is dropped. */
	void write(short[] samples, int frames) {
		SourceDataLine l = line;
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
		int free = l.available() & ~3;
		int len = Math.min(n, free);
		if (len > 0) l.write(bytes, 0, len);
	}

	/** Detach from the shared line (the line itself stays open, see class comment). */
	@Override
	public void close() {
		line = null;
	}
}
