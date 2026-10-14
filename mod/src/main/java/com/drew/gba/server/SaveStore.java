package com.drew.gba.server;

import com.drew.gba.save.SaveRules;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Per-pak save files in a directory ({@code world/gbalink/saves/<pakId>.sav}). A save's revision is its SHA-256. */
public final class SaveStore {
	private final Path dir;

	public SaveStore(Path dir) {
		this.dir = dir;
	}

	private Path file(UUID pakId) {
		return dir.resolve(pakId + ".sav");
	}

	public boolean has(UUID pakId) {
		return Files.isRegularFile(file(pakId));
	}

	/** The stored save, or an empty array if there is none (or it is unreadable). */
	public byte[] read(UUID pakId) {
		Path f = file(pakId);
		try {
			if (Files.isRegularFile(f) && Files.size(f) <= SaveRules.MAX_SAVE) return Files.readAllBytes(f);
		} catch (IOException e) {
			// treated as no save
		}
		return new byte[0];
	}

	/** Atomically writes the save and returns its new revision. */
	public String write(UUID pakId, byte[] data) throws IOException {
		writeAtomic(file(pakId), data);
		return SaveRules.revision(data);
	}

	/**
	 * Replaces the save with an imported one. The previous save (if any) is kept as {@code <pakId>.sav.bak1}; older
	 * backups shift to bak2 and bak3 and the oldest is dropped. Returns the new revision.
	 */
	public String writeImport(UUID pakId, byte[] data) throws IOException {
		Path f = file(pakId);
		if (Files.isRegularFile(f)) {
			rotateBackups(f, SaveRules.IMPORT_BACKUPS);
			Files.copy(f, backup(f, 1), StandardCopyOption.REPLACE_EXISTING);
		}
		writeAtomic(f, data);
		return SaveRules.revision(data);
	}

	private static Path backup(Path save, int n) {
		return save.resolveSibling(save.getFileName() + ".bak" + n);
	}

	/** bak(n-1) -> bak(n) for n = count..2 (the oldest falls off). */
	static void rotateBackups(Path save, int count) throws IOException {
		for (int n = count; n >= 2; n--) {
			Path from = backup(save, n - 1);
			if (Files.isRegularFile(from)) Files.move(from, backup(save, n), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public String revision(UUID pakId) {
		return SaveRules.revision(read(pakId));
	}

	public static void writeAtomic(Path target, byte[] data) throws IOException {
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(tmp, data);
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
