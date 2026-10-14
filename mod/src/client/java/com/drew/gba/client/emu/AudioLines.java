package com.drew.gba.client.emu;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * The mod's ONE JavaSound output line (32768 Hz 16-bit stereo, 100 ms buffer), opened once and never closed (see
 * {@link AudioOut}). The emulator (own game) and the CRT TVs heard in the world ({@code CrtAudio}) both write to it
 * through its {@link LineFeeder}; while a GBA session is running the TVs stay quiet (the handheld game wins).
 *
 * A second line was tried for the TVs and does not work in the game on macOS: opening it blocked in CoreAudio, and
 * once opened next to the first it never drained. One line is known to work.
 */
public final class AudioLines {
	public static final int RATE = GbaNative.AUDIO_RATE;
	public static final int BUFFER_BYTES = RATE / 10 * 4;

	private static boolean tried;
	private static LineFeeder feeder;

	private AudioLines() {}

	/** Opens the line (blocks while the device is being opened; call from a worker thread). Safe to call repeatedly. */
	public static synchronized void open() {
		if (tried) return;
		tried = true;
		try {
			AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt, BUFFER_BYTES);
			SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
			l.open(fmt, BUFFER_BYTES);
			l.start();
			feeder = LineFeeder.forLineWithVolume(l, RATE, "line");
			feeder.setStallMs(70); // the CRT TV stream arrives in ~33 ms packets; the emulator writes every ~17 ms
			LineFeeder.startTicking(feeder);
		} catch (Exception | Error ex) {
			org.slf4j.LoggerFactory.getLogger("gbalink").warn("audio line could not be opened: {}", ex.toString());
			feeder = null; // run silently
		}
	}

	/** True while an emulator session is writing to the line. */
	public static boolean gameActive() {
		return AudioOut.gameActive();
	}

	public static synchronized LineFeeder feeder() {
		return feeder;
	}
}
