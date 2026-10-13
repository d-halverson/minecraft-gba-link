package com.drew.gba.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** ROMs the player has put in {@code <gameDir>/gbalink/roms/}, with SHA-1 and header info cached. */
public final class RomLibrary {
	public record Rom(String file, long size, long mtime, String sha1, String name, String title, String code) {
		public Path path() {
			return romsDir().resolve(file);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path gameDir;

	private RomLibrary() {}

	public static void init(Path gameDirectory) {
		gameDir = gameDirectory;
	}

	public static Path baseDir() {
		return gameDir.resolve("gbalink");
	}

	public static Path romsDir() {
		return baseDir().resolve("roms");
	}

	private static Path indexFile() {
		return baseDir().resolve("rom_index.json");
	}

	/** Scan the ROM folder (hashing only new or changed files) and return all valid ROMs. */
	public static synchronized List<Rom> scan() {
		List<Rom> out = new ArrayList<>();
		try {
			Files.createDirectories(romsDir());
		} catch (IOException e) {
			return out;
		}
		Map<String, Rom> cache = loadIndex();
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.walk(romsDir(), 2)) {
			s.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gba"))
				.forEach(files::add);
		} catch (IOException e) {
			return out;
		}
		boolean changed = false;
		Map<String, Rom> fresh = new HashMap<>();
		for (Path p : files) {
			try {
				String rel = romsDir().relativize(p).toString().replace('\\', '/');
				long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
				Rom c = cache.get(rel);
				if (c == null || c.size() != size || c.mtime() != mtime) {
					c = describe(rel, p, size, mtime);
					changed = true;
				}
				if (c != null) {
					fresh.put(rel, c);
					out.add(c);
				}
			} catch (IOException e) {
				// unreadable file: skip
			}
		}
		if (changed || fresh.size() != cache.size()) saveIndex(fresh);
		out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return out;
	}

	public static Rom findBySha1(String sha1) {
		for (Rom r : scan()) if (r.sha1().equalsIgnoreCase(sha1)) return r;
		return null;
	}

	private static Rom describe(String rel, Path p, long size, long mtime) throws IOException {
		if (size < 0xC0 || size > 64L * 1024 * 1024) return null;
		try (InputStream in = Files.newInputStream(p)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[1 << 16];
			byte[] head = new byte[0xC0];
			int headFill = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
				if (headFill < head.length) {
					int c = Math.min(n, head.length - headFill);
					System.arraycopy(buf, 0, head, headFill, c);
					headFill += c;
				}
			}
			String title = ascii(head, 0xA0, 12);
			String code = ascii(head, 0xAC, 4);
			String file = p.getFileName().toString();
			String name = file.substring(0, file.length() - 4).trim();
			if (name.isEmpty()) name = title;
			return new Rom(rel, size, mtime, hex(md.digest()), name, title, code);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	private static String ascii(byte[] b, int off, int len) {
		StringBuilder sb = new StringBuilder();
		for (int i = off; i < off + len; i++) {
			int c = b[i] & 0xFF;
			if (c == 0) break;
			if (c >= 0x20 && c < 0x7F) sb.append((char) c);
		}
		return sb.toString().trim();
	}

	private static String hex(byte[] d) {
		StringBuilder sb = new StringBuilder(d.length * 2);
		for (byte x : d) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	private static Map<String, Rom> loadIndex() {
		try {
			if (!Files.isRegularFile(indexFile())) return new HashMap<>();
			List<Rom> list = GSON.fromJson(Files.readString(indexFile(), StandardCharsets.UTF_8), new TypeToken<List<Rom>>() {}.getType());
			Map<String, Rom> m = new HashMap<>();
			if (list != null) for (Rom r : list) if (r != null && r.file() != null && r.sha1() != null) m.put(r.file(), r);
			return m;
		} catch (Exception e) {
			return new HashMap<>();
		}
	}

	private static void saveIndex(Map<String, Rom> m) {
		try {
			Files.createDirectories(baseDir());
			Files.writeString(indexFile(), GSON.toJson(new ArrayList<>(m.values())), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}
}
