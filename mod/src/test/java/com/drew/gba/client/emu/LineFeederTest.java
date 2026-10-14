package com.drew.gba.client.emu;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Captures everything written to a fake audio device in each state and checks that a pause yields a short fade to
 * silence, then only zeros, with no underrun (the moment a real device would replay stale buffer contents).
 */
class LineFeederTest {
	static final int RATE = 32768;

	/** A device model: a ring that drains in (simulated) real time and records every byte it is given. */
	static final class FakeLine implements LineFeeder.Sink {
		final int size;
		int queued;
		long underrunBytes;
		final ByteArrayOutputStream played = new ByteArrayOutputStream(); // in write order == playback order
		boolean started;

		FakeLine(int size) { this.size = size; }
		@Override public int bufferSize() { return size; }
		@Override public int available() { return size - queued; }
		@Override public int write(byte[] b, int off, int len) {
			len = Math.min(len, available());
			played.write(b, off, len);
			queued += len;
			started = true;
			return len;
		}
		/** The device plays {@code ms} of audio. */
		void advance(double ms) {
			int n = ((int) (RATE * ms / 1000)) * 4;
			if (n > queued) {
				if (started) underrunBytes += n - queued;
				n = queued;
			}
			queued -= n;
		}
		short[] samples() {
			byte[] b = played.toByteArray();
			short[] s = new short[b.length / 2];
			for (int i = 0; i < s.length; i++) s[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
			return s;
		}
	}

	long now; // fake nanoClock
	FakeLine line = new FakeLine(RATE / 10 * 4); // 100 ms like AudioOut
	LineFeeder feeder = new LineFeeder(line, RATE, () -> now);
	double phase;

	/** One 16.7 ms emulator frame of a loud 440 Hz tone, followed by 16.7 ms of device time. */
	void playFrame() {
		write16();
		step(16.7);
	}

	/** The emulator runs a little ahead of the device, like the real thread (one frame of lead). */
	void primeLead() { write16(); }

	void write16() {
		int frames = 547;
		byte[] b = new byte[frames * 4];
		for (int i = 0; i < frames; i++) {
			short v = (short) (Math.sin(phase) * 20000);
			phase += 2 * Math.PI * 440 / RATE;
			b[i * 4] = (byte) v; b[i * 4 + 1] = (byte) (v >> 8); b[i * 4 + 2] = (byte) v; b[i * 4 + 3] = (byte) (v >> 8);
		}
		feeder.write(b, b.length);
	}

	/** Advance the clock and the device in 5 ms ticks like the feeder thread. */
	void step(double ms) {
		double left = ms;
		while (left > 0) {
			double d = Math.min(5, left);
			now += (long) (d * 1_000_000);
			line.advance(d);
			feeder.tick();
			left -= d;
		}
	}

	@Test
	void pauseFadesToZeroThenOnlySilenceWithoutUnderrun() {
		primeLead();
		for (int i = 0; i < 30; i++) playFrame(); // 0.5 s of running game
		long underrunsWhilePlaying = line.underrunBytes;
		int writtenBeforePause = line.samples().length;
		assertEquals(0, underrunsWhilePlaying, "no underruns while the game is running");

		step(1000); // paused for a second: nothing is written by the emulator
		assertTrue(line.underrunBytes < RATE * 0.020 * 4, "at most a brief gap before the ramp lands: " + line.underrunBytes);

		short[] all = line.samples();
		// find the end of the real audio: the last sample above the tone's noise floor is at most one fade after the pause
		int lastLoud = -1;
		for (int i = writtenBeforePause - 1; i < all.length; i++) if (Math.abs(all[i]) > 0) lastLoud = i;
		int fadeSamples = (int) (RATE * (LineFeeder.FADE_MS / 1000)) * 2;
		assertTrue(lastLoud < writtenBeforePause + fadeSamples + 4, "non-zero data after the pause is at most the fade (" + (lastLoud - writtenBeforePause) + " samples)");
		for (int i = lastLoud + 1; i < all.length; i++) assertEquals(0, all[i], "silence at sample " + i);
		assertTrue(all.length - lastLoud > RATE, "plenty of silence was fed (" + (all.length - lastLoud) + ")");
		long u = line.underrunBytes;
		step(2000);
		assertEquals(u, line.underrunBytes, "once silent the line never runs dry again, so nothing stale can be replayed");
		for (short v : line.samples()) { /* all samples already checked above; the extra 2 s are zeros too */ }
		short[] more = line.samples();
		for (int i = lastLoud + 1; i < more.length; i++) assertEquals(0, more[i]);
		assertTrue(line.queued > 0, "the line still holds (silent) data");
	}

	@Test
	void fadeOutIsSmoothNoClick() {
		primeLead();
		for (int i = 0; i < 20; i++) playFrame();
		step(500);
		short[] s = line.samples();
		int maxStep = 0;
		for (int i = 2; i < s.length; i++) maxStep = Math.max(maxStep, Math.abs(s[i] - s[i - 2])); // same channel
		// the tone itself moves up to ~ 20000*2*pi*440/32768 = 1700 per sample; the ramp must add no big jump
		assertTrue(maxStep < 2500, "no discontinuity larger than the tone's own slope, got " + maxStep);
		assertEquals(0, s[s.length - 1]);
	}

	@Test
	void resumeFadesInFromZeroAndKeepsLatencyLow() {
		primeLead();
		for (int i = 0; i < 20; i++) playFrame();
		step(800); // pause
		int before = line.samples().length;
		for (int i = 0; i < 6; i++) playFrame();
		short[] s = line.samples();
		// first audible sample after the pause starts near zero and ramps up
		int first = -1;
		for (int i = before; i < s.length; i++) if (s[i] != 0) { first = i; break; }
		assertTrue(first > 0, "audio resumed");
		assertTrue(Math.abs(s[first]) < 2500, "resume starts quietly: " + s[first]);
		int fade = (int) (RATE * (LineFeeder.FADE_MS / 1000));
		int peakInFade = 0, peakAfter = 0;
		for (int i = first; i < first + fade * 2 && i < s.length; i += 2) peakInFade = Math.max(peakInFade, Math.abs(s[i]));
		for (int i = first + fade * 4; i < s.length; i += 2) peakAfter = Math.max(peakAfter, Math.abs(s[i]));
		assertTrue(peakInFade < peakAfter, "fade-in is quieter than full level (" + peakInFade + " vs " + peakAfter + ")");
		assertTrue(line.underrunBytes < RATE * 0.020 * 4, "no underrun on resume beyond the initial stall: " + line.underrunBytes);
		assertTrue(line.queued < (int) (RATE * 0.050) * 4, "queued silence was cancelled, latency stays low (queued " + line.queued + " bytes)");
	}

	@Test
	void nothingWrittenUntilFirstAudio() {
		step(500);
		assertEquals(0, line.samples().length, "an idle, never-used line is left alone");
	}

	@Test
	void shortStallDoesNotInsertSilence() {
		primeLead();
		for (int i = 0; i < 20; i++) playFrame();
		int before = line.samples().length;
		step(2); // a hiccup shorter than the stall threshold
		playFrame();
		short[] s = line.samples();
		boolean zeroRun = false;
		int run = 0;
		for (int i = before; i < s.length; i++) {
			run = s[i] == 0 ? run + 1 : 0;
			if (run > 40) zeroRun = true;
		}
		assertFalse(zeroRun, "a short hiccup is not treated as a pause");
	}
}
