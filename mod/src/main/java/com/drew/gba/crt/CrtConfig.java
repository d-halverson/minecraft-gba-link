package com.drew.gba.crt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Server settings in config/gbalink.properties (created with defaults when missing). */
public record CrtConfig(int maxViewers, int viewDistance) {
	public static final int DEFAULT_MAX_VIEWERS = 10;
	public static final int DEFAULT_VIEW_DISTANCE = 48;

	public static CrtConfig defaults() {
		return new CrtConfig(DEFAULT_MAX_VIEWERS, DEFAULT_VIEW_DISTANCE);
	}

	/** Reads the file, creating it with defaults if it does not exist; bad or missing values fall back to defaults. */
	public static CrtConfig load(Path file) {
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				p.load(in);
			} catch (IOException ignored) {
			}
		} else {
			try {
				Files.createDirectories(file.getParent());
				p.setProperty("crt_max_viewers", String.valueOf(DEFAULT_MAX_VIEWERS));
				p.setProperty("crt_view_distance", String.valueOf(DEFAULT_VIEW_DISTANCE));
				try (OutputStream out = Files.newOutputStream(file)) {
					p.store(out, "GBA Link. crt_max_viewers: spectators per CRT TV. crt_view_distance: blocks within which players can watch a TV.");
				}
			} catch (IOException ignored) {
			}
		}
		return new CrtConfig(
			clamp(parse(p.getProperty("crt_max_viewers"), DEFAULT_MAX_VIEWERS), 0, 64),
			clamp(parse(p.getProperty("crt_view_distance"), DEFAULT_VIEW_DISTANCE), 4, 256));
	}

	private static int parse(String s, int def) {
		if (s == null) return def;
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
