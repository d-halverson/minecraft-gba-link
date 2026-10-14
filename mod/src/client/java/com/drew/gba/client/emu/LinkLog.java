package com.drew.gba.client.emu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Append-only text log (gbalink/link.log), rotated to link.log.1 once it grows past {@code maxBytes}.
 * Never throws: logging must not be able to disturb the emulator.
 */
public final class LinkLog {
	public static final long DEFAULT_MAX_BYTES = 1_000_000L;
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

	private final Path file;
	private final long maxBytes;
	private long size = -1;

	public LinkLog(Path file, long maxBytes) {
		this.file = file;
		this.maxBytes = maxBytes;
	}

	public static String line(long epochMillis, String title, String role, int players, String stats) {
		return TS.format(Instant.ofEpochMilli(epochMillis)) + " | " + title + " | " + role + " | players=" + players + " | " + stats;
	}

	public void append(String text) {
		try {
			Files.createDirectories(file.getParent());
			if (size < 0) size = Files.isRegularFile(file) ? Files.size(file) : 0;
			byte[] b = (text + "\n").getBytes(StandardCharsets.UTF_8);
			if (size + b.length > maxBytes && size > 0) {
				Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
				size = 0;
			}
			Files.write(file, b, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			size += b.length;
		} catch (IOException | RuntimeException e) {
			// best effort only
		}
	}
}
