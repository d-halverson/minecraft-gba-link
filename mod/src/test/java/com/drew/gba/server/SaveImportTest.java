package com.drew.gba.server;

import com.drew.gba.save.SaveRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The server side of "Import .sav": acceptance rules and the rolling bak1..3 backups. */
class SaveImportTest {
	@TempDir Path dir;

	private static byte[] save(int fill) {
		byte[] b = new byte[32768];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	@Test
	void importKeepsRollingBackups() throws Exception {
		SaveStore store = new SaveStore(dir);
		UUID pak = UUID.randomUUID();
		Path sav = dir.resolve(pak + ".sav");
		// no previous save: nothing to back up
		String r1 = store.writeImport(pak, save(1));
		assertEquals(SaveRules.revision(save(1)), r1);
		assertFalse(Files.exists(dir.resolve(pak + ".sav.bak1")));
		store.writeImport(pak, save(2));
		assertArrayEquals(save(1), Files.readAllBytes(dir.resolve(pak + ".sav.bak1")));
		store.writeImport(pak, save(3));
		store.writeImport(pak, save(4));
		store.writeImport(pak, save(5));
		assertArrayEquals(save(5), Files.readAllBytes(sav));
		assertArrayEquals(save(4), Files.readAllBytes(dir.resolve(pak + ".sav.bak1")));
		assertArrayEquals(save(3), Files.readAllBytes(dir.resolve(pak + ".sav.bak2")));
		assertArrayEquals(save(2), Files.readAllBytes(dir.resolve(pak + ".sav.bak3")));
		assertFalse(Files.exists(dir.resolve(pak + ".sav.bak4")), "only three backups are kept");
		assertEquals(SaveRules.revision(save(5)), store.revision(pak));
	}

	@Test
	void backupsAreNotMistakenForSaves() throws Exception {
		SaveStore store = new SaveStore(dir);
		UUID pak = UUID.randomUUID();
		store.write(pak, save(7));
		store.writeImport(pak, save(8));
		assertTrue(store.has(pak));
		assertArrayEquals(save(8), store.read(pak));
		UUID other = UUID.randomUUID();
		assertFalse(store.has(other));
	}

	@Test
	void acceptanceRules() {
		assertNull(SaveRules.importRejection(true, 32768));
		assertNull(SaveRules.importRejection(true, 8 * 1024));
		assertNotNull(SaveRules.importRejection(false, 32768), "only the player with the pak open");
		assertNotNull(SaveRules.importRejection(true, 12345), "not a GBA save size");
		assertNotNull(SaveRules.importRejection(true, SaveRules.MAX_SAVE + 1));
	}
}
