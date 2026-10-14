package com.drew.gba.client.crt;

import com.drew.gba.client.emu.LineFeeder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Spectator audio: 16 kHz stereo line, separate from the emulator's. Opened once and never stopped or closed
 * (closing a line can hang on macOS, see AudioOut); a {@link LineFeeder} fades out and feeds silence whenever no TV
 * is audible, so the idle line never replays stale buffer contents. Volume falls off with distance, pan follows the listener's facing.
 */
public final class CrtAudio {
	public static final float SILENT_BEYOND = 24f;
	private static volatile SourceDataLine line;
	private static volatile LineFeeder feeder;
	private static boolean tried;
	private static byte[] bytes = new byte[0];

	private CrtAudio() {}

	/** Opens the line off-thread (device calls can block). Safe to call repeatedly. */
	public static synchronized void ensureOpen() {
		if (tried) return;
		tried = true;
		Thread t = new Thread(() -> {
			try {
				AudioFormat fmt = new AudioFormat(16000, 16, 2, true, false);
				int buf = 16000 / 4 * 4; // 250 ms
				SourceDataLine l = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt, buf));
				l.open(fmt, buf);
				l.start();
				feeder = LineFeeder.forLine(l, 16000);
				LineFeeder.startTicking(feeder);
				line = l;
			} catch (Exception | Error e) {
				line = null; // silent
			}
		}, "gbalink-crt-audio-open");
		t.setDaemon(true);
		t.start();
	}

	public static boolean available() {
		return line != null;
	}

	/** Linear distance fall-off, 0 beyond SILENT_BEYOND. */
	public static float attenuation(double distance) {
		return (float) Math.max(0.0, Math.min(1.0, 1.0 - distance / SILENT_BEYOND));
	}

	/** Stereo gains for a pan in [-1 (left), 1 (right)] (constant power). */
	public static float[] pan(double pan) {
		double a = (Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4;
		return new float[] {(float) Math.cos(a), (float) Math.sin(a)};
	}

	/** Queue mono samples. {@code master} is Minecraft's master volume (0..1). Drops data when the line is backed up. */
	public static void play(short[] mono, int n, double distance, double pan, float master) {
		SourceDataLine l = line;
		LineFeeder fd = feeder;
		if (l == null || fd == null || n <= 0) return;
		float att = attenuation(distance);
		if (att <= 0f || master <= 0f) return;
		int queued = l.getBufferSize() - l.available();
		if (queued > 16000 / 8 * 4) return; // keep latency under ~125 ms
		float v = att * att * master * master;
		float[] g = pan(pan);
		int need = n * 4;
		if (bytes.length < need) bytes = new byte[need];
		for (int i = 0; i < n; i++) {
			int sl = (int) (mono[i] * v * g[0]), sr = (int) (mono[i] * v * g[1]);
			bytes[i * 4] = (byte) sl;
			bytes[i * 4 + 1] = (byte) (sl >> 8);
			bytes[i * 4 + 2] = (byte) sr;
			bytes[i * 4 + 3] = (byte) (sr >> 8);
		}
		fd.write(bytes, need);
	}
}
