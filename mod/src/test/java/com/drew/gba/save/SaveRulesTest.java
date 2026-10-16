package com.drew.gba.save;

import com.drew.gba.server.SaveStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SaveRulesTest {
	private static SuspendFile suspend(String rev, String rom) {
		return new SuspendFile(rev, rom, true, new byte[] {1, 2, 3});
	}

	@Test
	void revisionIsSha256() {
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SaveRules.revision(new byte[0]));
		assertEquals(SaveRules.EMPTY_REVISION, SaveRules.revision(null));
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SaveRules.revision("abc".getBytes()));
		assertNotEquals(SaveRules.revision(new byte[] {1}), SaveRules.revision(new byte[] {2}));
	}

	@Test
	void importSizes() {
		for (int base : new int[] {512, 8192, 32768, 65536, 131072}) {
			assertNull(SaveRules.validateImportSize(base), "base " + base);
			assertNull(SaveRules.validateImportSize(base + 16), "rtc " + base);
			assertNotNull(SaveRules.validateImportSize(base + 1));
			assertNotNull(SaveRules.validateImportSize(base - 1));
		}
		assertNotNull(SaveRules.validateImportSize(0));
		assertNotNull(SaveRules.validateImportSize(-5));
		assertNotNull(SaveRules.validateImportSize(512 * 1024 + 1));
		assertNotNull(SaveRules.validateImportSize(256 * 1024)); // not a GBA save size
		assertNotNull(SaveRules.validateImportSize(12345));
		assertTrue(SaveRules.validateImportSize(12345).contains("12345"));
	}

	@Test
	void resumeDecision() {
		String rev = SaveRules.revision(new byte[] {9});
		String rom = "a".repeat(40);
		assertEquals(SaveRules.Resume.NONE, SaveRules.decide(null, false, rev, rom));
		assertEquals(SaveRules.Resume.RESUME, SaveRules.decide(suspend(rev, rom), false, rev, rom));
		assertEquals(SaveRules.Resume.RESUME, SaveRules.decide(suspend(rev, rom.toUpperCase()), false, rev, rom));
		// the save changed on the server since the suspend was written
		assertEquals(SaveRules.Resume.DISCARD_CHANGED, SaveRules.decide(suspend(rev, rom), false, SaveRules.revision(new byte[] {8}), rom));
		// an unconfirmed upload is still fine if the server happens to hold exactly that save
		SuspendFile unconfirmed = new SuspendFile(rev, rom, false, new byte[] {1});
		assertEquals(SaveRules.Resume.RESUME, SaveRules.decide(unconfirmed, false, rev, rom));
		assertEquals(SaveRules.Resume.DISCARD_CHANGED, SaveRules.decide(unconfirmed, false, SaveRules.EMPTY_REVISION, rom));
		// unknown server revision (old server) never resumes
		assertEquals(SaveRules.Resume.DISCARD_CHANGED, SaveRules.decide(suspend(rev, rom), false, "", rom));
		assertEquals(SaveRules.Resume.DISCARD_INVALID, SaveRules.decide(suspend(rev, "b".repeat(40)), false, rev, rom)); // other ROM
		assertEquals(SaveRules.Resume.DISCARD_INVALID, SaveRules.decide(null, true, rev, rom));
	}

	@Test
	void importAcceptance() {
		assertNull(SaveRules.importRejection(true, 32768));
		assertNull(SaveRules.importRejection(true, 32768 + 16));                     // RTC footer
		assertNotNull(SaveRules.importRejection(false, 32768));                      // no open session for that pak
		assertNotNull(SaveRules.importRejection(true, 1000));                        // garbage size
		assertNotNull(SaveRules.importRejection(true, 600 * 1024));                  // too large
		assertNotNull(SaveRules.importRejection(true, 0));                           // empty
	}

	@Test
	void suspendFileRoundTrip() throws Exception {
		SuspendFile f = new SuspendFile("r".repeat(64), "c".repeat(40), false, new byte[] {5, 6, 7, 8});
		SuspendFile g = SuspendFile.decode(f.encode());
		assertNotNull(g);
		assertEquals(f.revision(), g.revision());
		assertEquals(f.romSha1(), g.romSha1());
		assertFalse(g.confirmed());
		assertArrayEquals(f.state(), g.state());
		byte[] enc = f.encode();
		assertNull(SuspendFile.decode(new byte[0]));
		assertNull(SuspendFile.decode("garbage".getBytes()));
		assertNull(SuspendFile.decode(java.util.Arrays.copyOf(enc, enc.length - 1))); // truncated
		byte[] extra = java.util.Arrays.copyOf(enc, enc.length + 1);
		assertNull(SuspendFile.decode(extra));
	}

	@Test
	void gameBoySaveSizes() {
		for (int base : SaveRules.GB_BASE_SIZES) {
			assertNull(SaveRules.validateImportSize(base, "gb"), "base " + base);
			assertNull(SaveRules.validateImportSize(base + 48, "gbc"), "mbc3 clock footer " + base);
			assertNull(SaveRules.validateImportSize(base + 44, "gb"), "old VBA footer " + base);
			assertNotNull(SaveRules.validateImportSize(base - 1, "gb"));
			assertNotNull(SaveRules.validateImportSize(base + 256, "gb"));
		}
		assertTrue(SaveRules.validateImportSize(12345, "gb").contains("Game Boy"));
		assertNotNull(SaveRules.validateImportSize(0, "gbc"));
		assertNotNull(SaveRules.validateImportSize(512 * 1024 + 1, "gbc"));
		assertNull(SaveRules.validateImportSize(2048, "gbc"), "a 2 KiB save is fine for Game Boy but not GBA");
		assertNotNull(SaveRules.validateImportSize(2048, "gba"));
		assertNull(SaveRules.validateImportSize(32768, null), "no system means GBA");
		assertNull(SaveRules.importRejection(true, 8192 + 48, "gb"));
		assertNotNull(SaveRules.importRejection(false, 8192, "gb"));
		assertNotNull(SaveRules.importRejection(true, 1000, "gbc"));
	}

	@Test
	void suspendFileRecordsTheSystem() throws Exception {
		SuspendFile gb = new SuspendFile("r".repeat(64), "c".repeat(40), true, new byte[] {1, 2}, "gbc");
		SuspendFile back = SuspendFile.decode(gb.encode());
		assertNotNull(back);
		assertEquals("gbc", back.system());
		assertEquals("gba", suspend("r", "c").system(), "default system is gba");
		assertEquals(SaveRules.Resume.RESUME, SaveRules.decide(gb, false, gb.revision(), gb.romSha1(), "gbc"));
		assertEquals(SaveRules.Resume.DISCARD_INVALID, SaveRules.decide(gb, false, gb.revision(), gb.romSha1(), "gba"));
		assertEquals(SaveRules.Resume.RESUME, SaveRules.decide(gb, false, gb.revision(), gb.romSha1()), "no system given: not checked");
		// a version 1 file (written by 0.4.x) still decodes, as a GBA suspend state
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream o = new java.io.DataOutputStream(bo);
		o.writeInt(0x47425353);
		o.writeByte(1);
		o.writeUTF("rev");
		o.writeUTF("c".repeat(40));
		o.writeBoolean(true);
		o.writeInt(3);
		o.write(new byte[] {9, 8, 7});
		SuspendFile old = SuspendFile.decode(bo.toByteArray());
		assertNotNull(old);
		assertEquals("gba", old.system());
		assertArrayEquals(new byte[] {9, 8, 7}, old.state());
		// unknown future versions are not guessed at
		byte[] future = gb.encode();
		future[4] = 9;
		assertNull(SuspendFile.decode(future));
	}

	@Test
	void fileNames() {
		assertEquals("Pokemon Emerald", SaveRules.safeFileName("Pokemon Emerald"));
		assertEquals("a_b_c", SaveRules.safeFileName("a/b\\c"));
		assertEquals("game", SaveRules.safeFileName(".."));
		assertEquals("game", SaveRules.safeFileName(""));
	}

	@Test
	void storeRevisionsFollowContent(@TempDir Path dir) throws Exception {
		SaveStore st = new SaveStore(dir.resolve("saves"));
		UUID id = UUID.randomUUID();
		assertFalse(st.has(id));
		assertEquals(SaveRules.EMPTY_REVISION, st.revision(id));
		byte[] a = new byte[512];
		a[0] = 1;
		String ra = st.write(id, a);
		assertTrue(st.has(id));
		assertEquals(ra, st.revision(id));
		assertEquals(SaveRules.revision(a), ra);
		byte[] b = a.clone();
		b[1] = 2;
		String rb = st.write(id, b);
		assertNotEquals(ra, rb);
		assertArrayEquals(b, st.read(id));
		assertEquals(0, Files.list(dir.resolve("saves")).filter(p -> p.toString().endsWith(".tmp")).count());
	}
}
