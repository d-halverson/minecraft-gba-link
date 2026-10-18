package com.drew.gba.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Per-player client settings, kept in {@code <gameDir>/config/gbalink-client.properties}. No Minecraft classes, so it
 * is unit-testable. Currently one setting: {@code gbaVolume} (0-100), the volume of the emulator audio and the CRT
 * spectator audio, independent of Minecraft's master volume.
 */
public final class ClientConfig {
	public static final String FILE_NAME = "gbalink-client.properties";
	public static final int DEFAULT_GBA_VOLUME = 100;

	private static volatile Path file;
	private static volatile int gbaVolume = DEFAULT_GBA_VOLUME;
	private static volatile EscMode escMode = EscMode.PAUSE;

	/** What Esc does while playing on a CRT TV (0.5.1): open the pause menu and freeze the game ("Paused" on the TV), or open it and keep the game running. */
	public enum EscMode {
		PAUSE("pause", "Pause"), KEEP_RUNNING("keep", "Keep running");
		public final String key, label;
		EscMode(String key, String label) { this.key = key; this.label = label; }
		public EscMode next() { return this == PAUSE ? KEEP_RUNNING : PAUSE; }
		public static EscMode parse(String s) {
			if (s != null) for (EscMode m : values()) if (m.key.equalsIgnoreCase(s.trim())) return m;
			return PAUSE;
		}
	}

	private ClientConfig() {}

	/** Load the config from {@code configDir} (created on the first save). Missing or unreadable values mean the defaults. */
	public static synchronized void init(Path configDir) {
		file = configDir.resolve(FILE_NAME);
		gbaVolume = DEFAULT_GBA_VOLUME;
		escMode = EscMode.PAUSE;
		if (!Files.isRegularFile(file)) return;
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		} catch (IOException | IllegalArgumentException e) {
			return;
		}
		gbaVolume = parsePercent(p.getProperty("gbaVolume"), DEFAULT_GBA_VOLUME);
		escMode = EscMode.parse(p.getProperty("crtEscMode"));
	}

	public static EscMode escMode() {
		return escMode;
	}

	public static void setEscMode(EscMode m) {
		escMode = m == null ? EscMode.PAUSE : m;
	}

	public static int parsePercent(String s, int def) {
		if (s == null) return def;
		try {
			return clampPercent(Integer.parseInt(s.trim()));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public static int clampPercent(int v) {
		return Math.max(0, Math.min(100, v));
	}

	/** GBA volume in percent (0-100). */
	public static int gbaVolume() {
		return gbaVolume;
	}

	public static void setGbaVolume(int percent) {
		gbaVolume = clampPercent(percent);
	}

	/**
	 * Linear sample gain for a volume percentage: a squared curve (like Minecraft's own sliders), so 50% is -12 dB
	 * rather than half amplitude. 100% is exactly 1.0 (unchanged samples) and 0% is silence.
	 */
	public static float gain(int percent) {
		float v = clampPercent(percent) / 100f;
		return v * v;
	}

	/** Current GBA volume as a sample gain; read by the audio lines on every write. */
	public static float gbaGain() {
		return gain(gbaVolume);
	}

	/** Write the config. Never throws (a read-only config dir just means the setting is not remembered). */
	public static synchronized void save() {
		Path f = file;
		if (f == null) return;
		Properties p = new Properties();
		p.setProperty("gbaVolume", Integer.toString(gbaVolume));
		p.setProperty("crtEscMode", escMode.key);
		try {
			Files.createDirectories(f.getParent());
			try (OutputStream out = Files.newOutputStream(f)) {
				p.store(out, "gbalink client settings. gbaVolume: 0-100, the GBA / CRT audio volume (independent of Minecraft's master volume). crtEscMode: pause | keep (what Esc does on a CRT TV)");
			}
		} catch (IOException ignored) {
		}
	}
}
