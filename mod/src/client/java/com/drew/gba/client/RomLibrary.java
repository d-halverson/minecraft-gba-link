package com.drew.gba.client;

import com.drew.gba.rom.RomHeader;
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

/** ROMs the player has put in {@code <gameDir>/gbalink/roms/} (.gba, .gb, .gbc), with SHA-1 and header info cached. */
public final class RomLibrary {
	/**
	 * {@code system} (0.5.0; entries cached by older versions have none and are GBA): {@link RomHeader#GBA},
	 * {@link RomHeader#GB} or {@link RomHeader#GBC}. {@code title} / {@code code} come from the header (see {@link RomHeader}).
	 */
	public record Rom(String file, long size, long mtime, String sha1, String name, String title, String code, String system, String warning) {
		public Rom {
			if (system == null || system.isEmpty()) system = RomHeader.GBA;
			if (warning == null) warning = "";
		}

		public Rom(String file, long size, long mtime, String sha1, String name, String title, String code, String system) {
			this(file, size, mtime, sha1, name, title, code, system, "");
		}

		public Path path() {
			return romsDir().resolve(file);
		}

		public boolean gameBoy() { return !RomHeader.GBA.equals(system); }

		/** {@code GBA}, {@code GB} or {@code GBC}. */
		public String tag() { return RomHeader.tag(system); }
	}

	/** A file in the ROM folder that was skipped, and why (shown in the burn list). */
	public record Rejected(String file, String reason) {}

	private static volatile List<Rejected> rejected = List.of();

	/** Files the last {@link #scan()} skipped because they are not valid ROMs (bad header, too large, ...). */
	public static List<Rejected> lastRejected() { return rejected; }

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

	public static Path savesDir() { return baseDir().resolve("saves"); }
	public static Path statesDir() { return baseDir().resolve("states"); }
	public static Path suspendDir() { return baseDir().resolve("suspend"); }
	public static Path exportsDir() { return baseDir().resolve("exports"); }
	public static Path importsDir() { return baseDir().resolve("imports"); }

	private static final String ROMS_README = String.join(System.lineSeparator(),
		"GBA Link - ROMs folder",
		"",
		"Put your own game files here: .gba (Game Boy Advance), .gb and .gbc (Game Boy / Game Boy Color).",
		"Subfolders one level deep are scanned too.",
		"Then hold a Blank Game Pak in Minecraft and press G (or right-click) to burn one onto it.",
		"",
		"Games are matched by the SHA-1 of the file, so everyone playing the same Game Pak needs the",
		"exact same ROM file. No games are included with the mod and none are downloaded for you.",
		"",
		"Other folders next to this one (gbalink/): imports (put .sav files here to burn a pak with an",
		"existing save), exports (Export .sav writes here), saves (a local copy of every save),",
		"suspend and states (your suspended games and F5 save states).",
		"");

	/** Creates the gbalink folders (and the ROMs README) so they exist from the first start of the game. */
	public static void ensureFolders() {
		try {
			Files.createDirectories(romsDir());
			Files.createDirectories(importsDir());
			// the rest of gbalink/ is created up front too, so every folder the docs mention exists after the first launch
			for (Path d : new Path[] {exportsDir(), savesDir(), statesDir(), suspendDir(), baseDir().resolve("natives")}) Files.createDirectories(d);
			Path readme = romsDir().resolve("README.txt");
			if (!Files.exists(readme)) Files.writeString(readme, ROMS_README, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("gbalink").warn("Could not create the gbalink folders under {}", baseDir(), e);
		}
	}

	/** Opens {@code dir} (created if needed) in the OS file manager. */
	public static void openFolder(Path dir) {
		try {
			Files.createDirectories(dir);
			com.mojang.blaze3d.Blaze3D.openPath(dir);
		} catch (IOException | RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("gbalink").warn("Could not open {}", dir, e);
		}
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
				.filter(p -> RomHeader.systemForFileName(p.getFileName().toString()) != null)
				.forEach(files::add);
		} catch (IOException e) {
			return out;
		}
		boolean changed = false;
		Map<String, Rom> fresh = new HashMap<>();
		List<Rejected> bad = new ArrayList<>();
		for (Path p : files) {
			try {
				String rel = romsDir().relativize(p).toString().replace('\\', '/');
				long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
				Rom c = cache.get(rel);
				if (c == null || c.size() != size || c.mtime() != mtime) {
					Object d = describe(rel, p, size, mtime);
					c = d instanceof Rom r ? r : null;
					if (d instanceof Rejected rj) bad.add(rj);
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
		rejected = List.copyOf(bad);
		out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return out;
	}

	public static Rom findBySha1(String sha1) {
		for (Rom r : scan()) if (r.sha1().equalsIgnoreCase(sha1)) return r;
		return null;
	}

	/** A {@link Rom}, or a {@link Rejected} with the reason the file is not a valid ROM. Only valid files are hashed. */
	private static Object describe(String rel, Path p, long size, long mtime) throws IOException {
		String file = p.getFileName().toString();
		byte[] head = new byte[RomHeader.HEAD_BYTES];
		int headFill = 0;
		try (InputStream in = Files.newInputStream(p)) {
			int n;
			while (headFill < head.length && (n = in.read(head, headFill, head.length - headFill)) > 0) headFill += n;
		}
		RomHeader.Result r = RomHeader.parse(file, size, java.util.Arrays.copyOf(head, headFill));
		if (!r.ok()) return new Rejected(rel, r.error());
		try (InputStream in = Files.newInputStream(p)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			int dot = file.lastIndexOf('.');
			String name = (dot > 0 ? file.substring(0, dot) : file).trim();
			RomHeader h = r.header();
			if (name.isEmpty()) name = h.title();
			return new Rom(rel, size, mtime, hex(md.digest()), name, h.title(), h.code(), h.system(), r.warning());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
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
