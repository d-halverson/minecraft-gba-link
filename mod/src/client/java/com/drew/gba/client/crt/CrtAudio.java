package com.drew.gba.client.crt;

import com.drew.gba.client.emu.LineFeeder;


/**
 * Spectator audio: 16 kHz stereo line, separate from the emulator's. Opened once and never stopped or closed
 * (closing a line can hang on macOS, see AudioOut); a {@link LineFeeder} fades out and feeds silence whenever no TV
 * is audible, so the idle line never replays stale buffer contents. Volume falls off with distance, pan follows the listener's facing.
 */
public final class CrtAudio {
	/** Full volume up to this many blocks, then a falling curve to silence at {@link #range()}. */
	/** Rate of the TV audio the server relays, and of the output line. */
	public static final int IN_RATE = 16000, OUT_RATE = com.drew.gba.client.emu.AudioLines.RATE;
	public static final float FULL_WITHIN = 4f;
	public static final float DEFAULT_RANGE = 24f;
	private static volatile float range = DEFAULT_RANGE;
	private static volatile LineFeeder feeder;
	private static boolean tried;
	private static byte[] bytes = new byte[0];
	public static volatile int lastQueued, skipped, written;

	private CrtAudio() {}

	/** Opens the shared line off-thread (device calls can block). Safe to call repeatedly. */
	public static synchronized void ensureOpen() {
		if (tried) return;
		tried = true;
		Thread t = new Thread(() -> {
			com.drew.gba.client.emu.AudioLines.open();
			feeder = com.drew.gba.client.emu.AudioLines.feeder();
		}, "gbalink-crt-audio-open");
		t.setDaemon(true);
		t.start();
	}

	public static boolean available() {
		return feeder != null;
	}

	/** The server's crt_audio_distance (blocks at which a TV is silent). */
	public static void setRange(int blocks) {
		range = Math.max(FULL_WITHIN + 2, blocks);
	}

	public static float range() {
		return range;
	}

	/** In-world loudness of a TV at {@code distance} blocks: 1 up to 4 blocks, falling linearly to 0 at the server's audio distance. */
	public static float attenuation(double distance) {
		return attenuation(distance, range);
	}

	public static float attenuation(double distance, float range) {
		return (float) Math.max(0.0, Math.min(1.0, 1.0 - (distance - FULL_WITHIN) / (range - FULL_WITHIN)));
	}

	/**
	 * Stereo gains for a pan in [-1 (left), 1 (right)]: constant power, scaled so a centred TV is exactly as loud on each
	 * side as the player's own game (1.0 / 1.0) and a hard pan puts it all on one side (never above 1).
	 */
	public static float[] pan(double pan) {
		double a = (Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4;
		return new float[] {(float) Math.min(1.0, Math.cos(a) * Math.sqrt(2)), (float) Math.min(1.0, Math.sin(a) * Math.sqrt(2))};
	}

	/**
	 * Queue mono samples of a TV heard in the world, at the TV volume ({@code tvVolume}, 0-100: the playing player's GBA
	 * setting, the same for every listener) times the distance curve. {@code master} is the listener's Minecraft master
	 * volume (0..1), so a TV behaves like any in-world sound. Drops data when the line is backed up.
	 */
	public static void play(short[] mono, int n, double distance, double pan, float master, int tvVolume) {
		LineFeeder fd = feeder;
		if (fd == null || n <= 0 || com.drew.gba.client.emu.AudioLines.gameActive()) return; // a running GBA game has the line
		float att = attenuation(distance);
		if (att <= 0f || tvVolume <= 0 || master <= 0f) return;
		int queued = fd.queuedBytes();
		lastQueued = queued;
		if (queued > OUT_RATE / 12 * 4) { skipped++; return; } // keep latency under ~80 ms // keep latency under ~125 ms
		float v = att * att * master * master * com.drew.gba.client.ClientConfig.gain(tvVolume);
		float[] g = pan(pan);
		int cap = (int) ((n + 2L) * OUT_RATE / IN_RATE) * 4 + 16;
		if (bytes.length < cap) bytes = new byte[cap];
		int m = resample(mono, n, g, v, bytes);
		int need = m * 4;
		written++;
		fd.write(bytes, need, false); // the TV level is the playing player's volume, not this listener's GBA knob
	}

	private static double phase;      // position of the next output frame relative to the next input block (-1..0)
	private static short lastSample;

	/** Linear 16 kHz mono to 32768 Hz stereo (gains {@code g} and {@code v} applied), continuous across blocks. Returns frames written. */
	static int resample(short[] mono, int n, float[] g, float v, byte[] out) {
		double step = (double) IN_RATE / OUT_RATE;
		double pos = phase;
		int m = 0;
		while ((int) Math.floor(pos) < n - 1) {
			int i0 = (int) Math.floor(pos);
			double frac = pos - i0;
			double s0 = i0 < 0 ? lastSample : mono[i0];
			double s1 = mono[i0 + 1];
			double smp = (s0 + (s1 - s0) * frac) * v;
			int sl = (int) (smp * g[0]), sr = (int) (smp * g[1]);
			out[m * 4] = (byte) sl;
			out[m * 4 + 1] = (byte) (sl >> 8);
			out[m * 4 + 2] = (byte) sr;
			out[m * 4 + 3] = (byte) (sr >> 8);
			m++;
			pos += step;
		}
		phase = pos - n;
		lastSample = mono[n - 1];
		return m;
	}
}
