package com.drew.gba.client;

import com.drew.gba.client.ControlMap.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ControlMapTest {
	@Test void defaultsMatchTheOldHardcodedKeys() {
		ControlMap m = new ControlMap();
		assertTrue(m.isDefault());
		assertEquals(Action.A, m.actionFor(27));      // X
		assertEquals(Action.B, m.actionFor(29));      // Z
		assertEquals(Action.L, m.actionFor(4));       // A
		assertEquals(Action.R, m.actionFor(22));      // S
		assertEquals(Action.START, m.actionFor(40));  // Enter
		assertEquals(Action.START, m.actionFor(88));  // keypad Enter alias
		assertEquals(Action.SELECT, m.actionFor(42)); // Backspace
		assertEquals(Action.UP, m.actionFor(82));
		assertEquals(Action.TURBO, m.actionFor(44));  // Space
		assertEquals(Action.TURBO, m.actionFor(43));  // Tab
		assertEquals(Action.SAVE_STATE, m.actionFor(62));
		assertEquals(Action.LOAD_STATE, m.actionFor(65));
		assertEquals(Action.POWER, m.actionFor(19));
		assertEquals(-1, m.bitFor(5));
		assertEquals(0, m.bitFor(27)); // GBA bit order: A = 0
		assertEquals(3, m.bitFor(40)); // Start = 3
	}

	@Test void rebindReplacesAndDropsAlias() {
		ControlMap m = new ControlMap();
		assertNull(m.bind(Action.START, 20));
		assertEquals(Action.START, m.actionFor(20));
		assertNull(m.actionFor(40));
		assertNull(m.actionFor(88)); // alias gone with the rebind
		assertFalse(m.isDefault());
	}

	@Test void conflictSwapsKeys() {
		ControlMap m = new ControlMap();
		assertEquals(Action.B, m.bind(Action.A, 29)); // A takes Z from B; B gets X
		assertEquals(Action.A, m.actionFor(29));
		assertEquals(Action.B, m.actionFor(27));
	}

	@Test void conflictWithoutOldKeyLeavesOtherUnbound() {
		ControlMap m = new ControlMap();
		m.clear(Action.A);
		assertEquals(Action.B, m.bind(Action.A, 29));
		assertEquals(ControlMap.NONE, m.primary(Action.B));
		assertEquals(Action.A, m.actionFor(29));
	}

	@Test void takingOneAliasKeepsTheOtherBound() {
		ControlMap m = new ControlMap();
		assertEquals(Action.START, m.bind(Action.A, 40)); // Enter -> A; Start keeps keypad Enter
		assertEquals(Action.A, m.actionFor(40));
		assertEquals(Action.START, m.actionFor(88));
		assertNull(m.actionFor(27));
	}

	@Test void resetRestoresDefaults() {
		ControlMap m = new ControlMap();
		m.bind(Action.A, 20);
		m.clear(Action.POWER);
		m.resetDefaults();
		assertTrue(m.isDefault());
	}

	@Test void reservedKeysCannotBeBound() {
		assertFalse(ControlMap.canBind(41)); // Esc
		assertFalse(ControlMap.canBind(45)); // -
		assertFalse(ControlMap.canBind(8));  // E
		assertFalse(ControlMap.canBind(ControlMap.NONE));
		assertTrue(ControlMap.canBind(20));
	}

	@Test void saveAndReload(@TempDir Path dir) {
		ControlMap.init(dir);
		ControlMap m = ControlMap.get();
		m.bind(Action.A, 20);
		m.bind(Action.SAVE_STATE, 66);
		m.clear(Action.POWER);
		m.save();
		assertTrue(Files.isRegularFile(dir.resolve(ControlMap.FILE_NAME)));
		ControlMap.init(dir);
		ControlMap r = ControlMap.get();
		assertEquals(Action.A, r.actionFor(20));
		assertEquals(Action.SAVE_STATE, r.actionFor(66));
		assertEquals(ControlMap.NONE, r.primary(Action.POWER));
		assertEquals(Action.START, r.actionFor(88)); // default alias survives a round trip
		ControlMap.init(dir.resolve("none")); // leave a clean map for other tests
	}

	@Test void missingFileMeansDefaults(@TempDir Path dir) {
		ControlMap.init(dir.resolve("nope"));
		assertTrue(ControlMap.get().isDefault());
	}

	@Test void corruptFileMeansDefaults(@TempDir Path dir) throws Exception {
		Files.write(dir.resolve(ControlMap.FILE_NAME), new byte[] {0, (byte) 0xff, '\\', 'u', 'x', 'x', 'x', 'x', '=', 1, 2, 3});
		ControlMap.init(dir);
		assertTrue(ControlMap.get().isDefault());
	}

	@Test void unreadableFileIsADirectory(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir.resolve(ControlMap.FILE_NAME));
		ControlMap.init(dir);
		assertTrue(ControlMap.get().isDefault());
		ControlMap.get().save(); // must not throw
	}

	@Test void badValuesFallBackPerAction(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(ControlMap.FILE_NAME), "A=notanumber\nB=20\nSTART=\nPOWER=41\nSELECT=-3\n");
		ControlMap.init(dir);
		ControlMap m = ControlMap.get();
		assertEquals(Action.A, m.actionFor(27)); // default
		assertEquals(Action.B, m.actionFor(20));
		assertEquals(ControlMap.NONE, m.primary(Action.START)); // explicitly unbound
		assertEquals(Action.POWER, m.actionFor(19)); // reserved key refused: default
		assertEquals(Action.SELECT, m.actionFor(42));
		ControlMap.init(dir.resolve("none"));
	}

	@Test void duplicateKeysInFileMeanDefaults(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(ControlMap.FILE_NAME), "A=20\nB=20\n");
		ControlMap.init(dir);
		assertTrue(ControlMap.get().isDefault());
	}
}
