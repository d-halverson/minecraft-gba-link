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
