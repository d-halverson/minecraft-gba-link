package com.drew.gba.client.emu;

import com.drew.gba.client.ClientConfig;
import com.drew.gba.crt.CrtConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Volume math, the client config file, and the GBA volume applied in what reaches the (fake) audio device. */
class VolumeTest {
	@Test
	void gainCurve() {
		assertEquals(1f, ClientConfig.gain(100), 0f);
		assertEquals(0f, ClientConfig.gain(0), 0f);
		assertEquals(0.25f, ClientConfig.gain(50), 1e-6);
		assertEquals(1f, ClientConfig.gain(250), 0f, "clamped");
		assertEquals(0f, ClientConfig.gain(-5), 0f, "clamped");
		assertEquals(100, ClientConfig.clampPercent(101));
		assertEquals(37, ClientConfig.parsePercent(" 37 ", 100));
		assertEquals(100, ClientConfig.parsePercent("loud", 100));
	}

	@Test
	void configPersists(@TempDir Path dir) throws Exception {
		ClientConfig.init(dir);
		assertEquals(100, ClientConfig.gbaVolume(), "default");
		ClientConfig.setGbaVolume(35);
		ClientConfig.save();
		assertTrue(Files.isRegularFile(dir.resolve("gbalink-client.properties")));
		ClientConfig.setGbaVolume(90);
		ClientConfig.init(dir); // a new game session reads it back
		assertEquals(35, ClientConfig.gbaVolume());
		Files.writeString(dir.resolve("gbalink-client.properties"), "gbaVolume=banana\n");
		ClientConfig.init(dir);
		assertEquals(100, ClientConfig.gbaVolume(), "bad value falls back to the default");
		ClientConfig.init(dir.resolve("nowhere"));
		assertEquals(100, ClientConfig.gbaVolume());
		ClientConfig.setGbaVolume(100);
	}

	/** Plays a constant tone through a LineFeeder and returns the loudest sample that reached the device. */
	private static int peakAfter(double gain, int frames) {
		LineFeederTest.FakeLine line = new LineFeederTest.FakeLine(32768 / 10 * 4);
		long[] now = {0};
		LineFeeder f = new LineFeeder(line, 32768, () -> now[0]);
		f.setGainSource(() -> gain);
		for (int rep = 0; rep < 20; rep++) {
			byte[] b = new byte[frames * 4];
			for (int i = 0; i < frames; i++) { b[i * 4] = (byte) 0x10; b[i * 4 + 1] = 0x4E; b[i * 4 + 2] = (byte) 0x10; b[i * 4 + 3] = 0x4E; } // 20000
			f.write(b, b.length);
			now[0] += 16_000_000;
			line.advance(16);
		}
		int peak = 0;
		for (short s : line.samples()) peak = Math.max(peak, Math.abs(s));
		return peak;
	}

	@Test
	void gbaVolumeIsAppliedToWhatReachesTheDevice() {
		assertEquals(20000, peakAfter(1.0, 500), 30);
		assertEquals(5000, peakAfter(ClientConfig.gain(50), 500), 30); // 50% = gain 0.25
		assertEquals(0, peakAfter(ClientConfig.gain(0), 500));
	}

	@Test
	void gainChangesGlideWithoutClicks() {
		LineFeederTest.FakeLine line = new LineFeederTest.FakeLine(32768 / 10 * 4);
		long[] now = {0};
		LineFeeder f = new LineFeeder(line, 32768, () -> now[0]);
		double[] g = {1.0};
		f.setGainSource(() -> g[0]);
		int frames = 547;
		for (int rep = 0; rep < 12; rep++) {
			if (rep == 6) g[0] = 0.0; // the wheel is turned to zero mid-game
			byte[] b = new byte[frames * 4];
			for (int i = 0; i < frames; i++) { b[i * 4] = (byte) 0x10; b[i * 4 + 1] = 0x4E; b[i * 4 + 2] = (byte) 0x10; b[i * 4 + 3] = 0x4E; }
			f.write(b, b.length);
			now[0] += 16_700_000;
			line.advance(16.7);
		}
		short[] s = line.samples();
		int maxStep = 0, zeroAt = -1;
		for (int i = 2; i < s.length; i += 2) {
			maxStep = Math.max(maxStep, Math.abs(s[i] - s[i - 2]));
			if (zeroAt < 0 && i > 6 * frames * 2 && s[i] == 0) zeroAt = i / 2;
		}
		assertTrue(maxStep < 200, "no click: biggest sample-to-sample jump " + maxStep);
		assertTrue(zeroAt > 0, "reached silence");
		assertEquals(0, s[s.length - 2]);
	}

	@Test
	void crtDistanceCurve() {
		float r = 24f;
		assertEquals(1f, com.drew.gba.client.crt.CrtAudio.attenuation(0, r), 0f);
		assertEquals(1f, com.drew.gba.client.crt.CrtAudio.attenuation(4, r), 0f, "full within 4 blocks");
		assertEquals(0.6f, com.drew.gba.client.crt.CrtAudio.attenuation(12, r), 1e-6);
		assertEquals(0f, com.drew.gba.client.crt.CrtAudio.attenuation(24, r), 0f);
		assertEquals(0f, com.drew.gba.client.crt.CrtAudio.attenuation(30, r), 0f);
		assertEquals(1f, com.drew.gba.client.crt.CrtAudio.attenuation(2, 40f), 0f);
	}

	@Test
	void audioDistanceConfig(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("gbalink.properties");
		CrtConfig c = CrtConfig.load(f);
		assertEquals(24, c.audioDistance());
		assertTrue(Files.readString(f).contains("crt_audio_distance=24"));
		Files.writeString(f, "crt_audio_distance=40\ncrt_view_distance=30\n");
		c = CrtConfig.load(f);
		assertEquals(40, c.audioDistance());
		assertEquals(30, c.effectiveAudioDistance(), "never beyond the view distance");
	}
}
