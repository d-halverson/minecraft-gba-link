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
