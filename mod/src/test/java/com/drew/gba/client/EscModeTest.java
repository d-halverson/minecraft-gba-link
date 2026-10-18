package com.drew.gba.client;

import com.drew.gba.client.emu.FrameBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EscModeTest {
	@Test
	void defaultIsPauseAndTheChoiceIsRemembered(@TempDir Path dir) throws Exception {
		ClientConfig.init(dir);
		assertEquals(ClientConfig.EscMode.PAUSE, ClientConfig.escMode());
		ClientConfig.setEscMode(ClientConfig.EscMode.KEEP_RUNNING);
		ClientConfig.setGbaVolume(40);
		ClientConfig.save();
		assertTrue(Files.readString(dir.resolve(ClientConfig.FILE_NAME)).contains("crtEscMode=keep"));
		ClientConfig.setEscMode(ClientConfig.EscMode.PAUSE);
		ClientConfig.init(dir); // a restart
		assertEquals(ClientConfig.EscMode.KEEP_RUNNING, ClientConfig.escMode());
		assertEquals(40, ClientConfig.gbaVolume(), "the volume setting is unaffected");
		ClientConfig.setEscMode(ClientConfig.EscMode.PAUSE);
		ClientConfig.save();
		ClientConfig.init(dir);
		assertEquals(ClientConfig.EscMode.PAUSE, ClientConfig.escMode());
	}

	@Test
	void damagedOrMissingValuesMeanPause(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(ClientConfig.FILE_NAME), "crtEscMode=banana\ngbaVolume=70\n");
		ClientConfig.init(dir);
		assertEquals(ClientConfig.EscMode.PAUSE, ClientConfig.escMode());
		assertEquals(ClientConfig.EscMode.KEEP_RUNNING, ClientConfig.EscMode.PAUSE.next());
		assertEquals(ClientConfig.EscMode.PAUSE, ClientConfig.EscMode.KEEP_RUNNING.next());
		assertEquals(ClientConfig.EscMode.KEEP_RUNNING, ClientConfig.EscMode.parse(" KEEP "));
		ClientConfig.setEscMode(null);
		assertEquals(ClientConfig.EscMode.PAUSE, ClientConfig.escMode());
	}

	@Test
	void pausedOverlayPaintsGreenOnTheTvCanvasOnly() {
		FrameBuffer fb = new FrameBuffer();
		fb.publish(d -> java.util.Arrays.fill(d, (byte) 0x80));
		byte[] plain = new byte[240 * 160 * 4], over = new byte[240 * 160 * 4];
		fb.copyLetterboxed(plain);
		int seq = fb.seq();
		fb.setPausedOverlay(true);
		assertTrue(fb.seq() > seq, "consumers are told to redraw");
		fb.copyLetterboxed(over);
		assertFalse(java.util.Arrays.equals(plain, over));
		boolean green = false;
		for (int i = 0; i < over.length; i += 4) if ((over[i] & 0xFF) == 0x3C && (over[i + 1] & 0xFF) == 0xE0 && (over[i + 2] & 0xFF) == 0x5A) green = true;
		assertTrue(green);
		fb.setPausedOverlay(false);
		byte[] again = new byte[240 * 160 * 4];
		fb.copyLetterboxed(again);
		assertArrayEquals(plain, again);
	}
}
