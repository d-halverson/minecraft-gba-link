package com.drew.gba.client.emu;

import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Keeps a never-closed JavaSound line well behaved while the audio source comes and goes.
 *
 * A line that is started but not fed underruns, and on some platforms (notably Windows) the device then keeps
 * replaying the stale contents of its ring buffer: little flickers and clicks while a game is paused, the screen is
 * closed, the emulator thread waits, and so on. Closing or stopping the line instead is unsafe on macOS (it can hang,
 * see {@link AudioOut}). So the line stays open and this class always keeps it fed:
 *
 * <ul>
 *   <li>producers call {@link #write}; the first write after silence is faded in over ~10 ms;</li>
 *   <li>when the producer has stalled and the real audio is about to run out, {@link #tick} appends a ~10 ms ramp
 *       from the last sample to zero and then keeps the line topped up with zeros, so it never underruns and stale
 *       data is never replayed.</li>
 * </ul>
 *
 * The player's GBA volume is applied here too, as a linear gain that glides to its target over about
 * {@link #GAIN_RAMP_MS} (per sample, full scale), so turning the volume wheel never clicks.
 *
 * Everything here is plain 16-bit little-endian stereo PCM, no Minecraft classes; the line is behind {@link Sink}
 * so the behaviour can be tested with a fake device.
 */
public final class LineFeeder {
	/** The device side: a thin view of a SourceDataLine. Never blocks (callers respect {@link #available}). */
	public interface Sink {
		int bufferSize();
		int available();
		int write(byte[] b, int off, int len);
	}

	public static final int FRAME_BYTES = 4;
	/** Fade length on pause / resume. */
	public static final double FADE_MS = 10;
	/** No write for this long counts as "the producer stopped". */
	public static final double STALL_MS = 22;
	/** The ramp to silence is queued once no more than this much real audio is still waiting in the line. */
	public static final double RAMP_WHEN_QUEUED_MS = 50;
	/** After resuming, this much (silence) stays queued ahead of the game audio as a jitter cushion. */
	public static final double KEEP_QUEUED_MS = 15;
	/** While silent the line is kept filled to this level with zeros. */
	public static final double SILENCE_FILL_MS = 60;
	/** A gain change from silence to full (or back) takes this long; smaller changes proportionally less. */
	public static final double GAIN_RAMP_MS = 30;

	private final Sink sink;
	private final int rate;
	private final LongSupplier nanos;
	private final int fadeFrames;
	private final double gainStep;       // gain change per frame while gliding to a new target
	private volatile double stallMs = STALL_MS;
	private volatile DoubleSupplier gainSource = () -> 1.0;
	private double curGain = -1;         // the gain of the last frame written (-1: nothing written yet)

	private boolean everWritten;
	private boolean silent = true;   // zeros are being fed (or nothing was ever fed)
	private boolean rampedDown;      // the ramp to zero has been queued for the current stall
	private long lastWriteNanos;
	private int lastL, lastR;        // last frame handed to the device
	private int fadeInLeft;
	private int skipLeft;            // leading bytes of the resumed audio dropped to cancel the queued silence (latency)
	private byte[] zeros = new byte[0];

	public LineFeeder(Sink sink, int sampleRate, LongSupplier nanos) {
		this.sink = sink;
		this.rate = sampleRate;
		this.nanos = nanos;
		this.fadeFrames = Math.max(1, (int) (sampleRate * FADE_MS / 1000));
		this.gainStep = 1.0 / Math.max(1, sampleRate * GAIN_RAMP_MS / 1000);
	}

	/** How long without a write counts as "the producer stopped" (default {@link #STALL_MS}; slower producers like the CRT stream need more). */
	public void setStallMs(double ms) {
		this.stallMs = ms;
	}

	/** Where the (0..1) linear gain comes from; read on every {@link #write}. Default: constant 1. */
	public void setGainSource(DoubleSupplier s) {
		this.gainSource = s;
	}

	public static LineFeeder forLine(SourceDataLine line, int sampleRate) {
		return new LineFeeder(new Sink() {
			@Override public int bufferSize() { return line.getBufferSize(); }
			@Override public int available() { return line.available(); }
			@Override public int write(byte[] b, int off, int len) { return line.write(b, off, len); }
		}, sampleRate, System::nanoTime);
	}

	/** The line for the real device; with {@code withVolume} the output is scaled by the player's GBA volume. */
	public static LineFeeder forLineWithVolume(SourceDataLine line, int sampleRate, String name) {
		LineFeeder f = forLine(line, sampleRate);
		if (System.getProperty("gbalink.audiolog") != null) f = new LineFeeder(new RmsLogSink(f.sink, name), sampleRate, System::nanoTime); // dev: log what reaches the device
		f.setGainSource(com.drew.gba.client.ClientConfig::gbaGain);
		return f;
	}

	/** Dev (-Dgbalink.audiolog): logs the RMS (16-bit scale) of everything handed to the device, once per second. */
	private static final class RmsLogSink implements Sink {
		private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gbalink-audio");
		private final Sink in;
		private final String name;
		private double sumSq;
		private long n, windowStart = System.nanoTime();

		RmsLogSink(Sink in, String name) {
			this.in = in;
			this.name = name;
		}

		@Override public int bufferSize() { return in.bufferSize(); }
		@Override public int available() { return in.available(); }
		@Override public int write(byte[] b, int off, int len) {
			int w = in.write(b, off, len);
			for (int i = off; i + 1 < off + w; i += 2) {
				int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
				sumSq += (double) v * v;
				n++;
			}
			long now = System.nanoTime();
			if (now - windowStart >= 1_000_000_000L) {
				LOG.info("[AUDIO-RMS] {} line rms={} samples={}", name, Math.round(n == 0 ? 0 : Math.sqrt(sumSq / n)), n);
				sumSq = 0;
				n = 0;
				windowStart = now;
			}
			return w;
		}
	}

	private int msToBytes(double ms) {
		return ((int) (rate * ms / 1000)) * FRAME_BYTES;
	}

	/** Bytes waiting in the line. */
	private int queued() {
		return sink.bufferSize() - sink.available();
	}

	/**
	 * Queue real audio (interleaved 16-bit LE stereo, {@code len} bytes, a multiple of 4). The buffer may be modified
	 * (fade-in). Whatever does not fit is dropped; it never blocks.
	 */
	public synchronized void write(byte[] pcm, int len) {
		write(pcm, len, true);
	}

	/** Bytes waiting in the line (for producers that limit their own latency). */
	public synchronized int queuedBytes() {
		return queued();
	}

	/** As {@link #write(byte[], int)}; {@code volume} false bypasses the gain source (the TV level is not the listener's knob). */
	public synchronized void write(byte[] pcm, int len, boolean volume) {
		len &= ~3;
		if (len <= 0) return;
		long now = nanos.getAsLong();
		lastWriteNanos = now;
		if (silent) { // coming back from silence (or the very first write): fade in
			silent = false;
			fadeInLeft = fadeFrames;
			// the zeros still queued would delay the game by that much for good; drop as much of its first audio
			skipLeft = Math.max(0, queued() - msToBytes(KEEP_QUEUED_MS)) & ~3;
			everWritten = true;
		}
		rampedDown = false;
		if (skipLeft > 0) {
			int skip = Math.min(skipLeft, len);
			skipLeft -= skip;
			if (skip == len) return;
			System.arraycopy(pcm, skip, pcm, 0, len - skip);
			len -= skip;
		}
		int fit = Math.min(len, sink.available() & ~3);
		if (fit <= 0) return;
		applyGain(pcm, fit / FRAME_BYTES, volume);
		if (fadeInLeft > 0) {
			int frames = fit / FRAME_BYTES;
			for (int f = 0; f < frames && fadeInLeft > 0; f++, fadeInLeft--) {
				float g = 1f - (float) fadeInLeft / fadeFrames;
				scale(pcm, f * FRAME_BYTES, g);
			}
		}
		int w = sink.write(pcm, 0, fit);
		if (w >= FRAME_BYTES) {
			int o = (w & ~3) - FRAME_BYTES;
			lastL = s16(pcm, o);
			lastR = s16(pcm, o + 2);
		}
	}

	/** Scale the first {@code frames} frames by the volume gain, gliding from the previous gain to the current target. */
	private void applyGain(byte[] pcm, int frames, boolean volume) {
		double target = volume ? Math.max(0.0, Math.min(1.0, gainSource.getAsDouble())) : 1.0;
		if (curGain < 0) curGain = target; // the first audio ever: no glide (the fade-in covers the start)
		if (curGain == 1.0 && target == 1.0) return;
		double g = curGain;
		for (int f = 0; f < frames; f++) {
			if (g < target) g = Math.min(target, g + gainStep);
			else if (g > target) g = Math.max(target, g - gainStep);
			if (g != 1.0) scale(pcm, f * FRAME_BYTES, (float) g);
		}
		curGain = g;
	}

	/** Call every ~5 ms from a timer. Cheap when the producer is running. */
	public synchronized void tick() {
		if (!everWritten) return; // nobody ever played: leave the device alone
		long idleNanos = nanos.getAsLong() - lastWriteNanos;
		boolean stalled = idleNanos > (long) (stallMs * 1_000_000);
		if (!silent) {
			if (!stalled || queued() > msToBytes(RAMP_WHEN_QUEUED_MS)) return;
			// producer stopped and the real audio is about to run out: fade the last sample to zero, then silence
			if (!rampedDown) {
				queueRamp();
				rampedDown = true;
			}
			silent = true;
		}
		fillSilence();
	}

	private void queueRamp() {
		int frames = Math.min(fadeFrames, (sink.available() & ~3) / FRAME_BYTES);
		if (frames <= 0) return;
		byte[] b = new byte[frames * FRAME_BYTES];
		for (int f = 0; f < frames; f++) {
			float g = 1f - (f + 1f) / frames; // ends exactly at 0
			put(b, f * FRAME_BYTES, Math.round(lastL * g));
			put(b, f * FRAME_BYTES + 2, Math.round(lastR * g));
		}
		sink.write(b, 0, b.length);
		lastL = 0;
		lastR = 0;
	}

	private void fillSilence() {
		int want = Math.min(msToBytes(SILENCE_FILL_MS), sink.bufferSize()) - queued();
		want = Math.min(want, sink.available()) & ~3;
		if (want <= 0) return;
		if (zeros.length < want) zeros = new byte[want];
		sink.write(zeros, 0, want);
		lastL = 0;
		lastR = 0;
	}

	private static void scale(byte[] b, int o, float g) {
		put(b, o, Math.round(s16(b, o) * g));
		put(b, o + 2, Math.round(s16(b, o + 2) * g));
	}

	private static int s16(byte[] b, int o) {
		return (short) ((b[o] & 0xFF) | (b[o + 1] << 8));
	}

	private static void put(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}

	// ------------------------------------------------------------------ shared ticker

	private static final List<LineFeeder> ACTIVE = new ArrayList<>();
	private static Thread ticker;

	/** Register for periodic {@link #tick} calls from one shared daemon thread (started on first use). */
	public static void startTicking(LineFeeder f) {
		synchronized (ACTIVE) {
			ACTIVE.add(f);
			if (ticker != null) return;
			ticker = new Thread(() -> {
				while (true) {
					try {
						Thread.sleep(5);
					} catch (InterruptedException e) {
						return;
					}
					LineFeeder[] all;
					synchronized (ACTIVE) { all = ACTIVE.toArray(new LineFeeder[0]); }
					for (LineFeeder lf : all) {
						try { lf.tick(); } catch (RuntimeException ignored) { }
					}
				}
			}, "gbalink-audio-feeder");
			ticker.setDaemon(true);
			ticker.start();
		}
	}
}
