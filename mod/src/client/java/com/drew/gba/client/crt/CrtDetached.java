package com.drew.gba.client.crt;

import com.drew.gba.client.ClientConfig;
import com.drew.gba.client.GbaLinkClient;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * A CRT session whose screen the player has left (0.5.3): the player walks around again while the TV in the world keeps
 * showing the game. "Pause" mode: the emulation stands still, the TV shows the last frame with a green PAUSED (also in the
 * spectators' stream). "Keep running" mode: emulation, stream and sound go on. It ends (save flushed, suspend state written, TV
 * released on the server) when: the player uses the TV again (the screen is re-attached instead), the server ends the play
 * (TV broken, owner walked more than ~18 blocks away, other dimension), the owner disconnects, plays another game, powers the
 * game off, or after {@link #MAX_PAUSED_MINUTES} / {@link #MAX_RUNNING_MINUTES} (so an abandoned TV is not held forever).
 */
public final class CrtDetached {
	public static final int MAX_PAUSED_MINUTES = 30, MAX_RUNNING_MINUTES = 15;

	private static EmulatorSession session;
	private static UUID crtId;
	private static long sinceNanos;
	private static boolean pausedMode;

	private CrtDetached() {}

	/** The player left the CRT screen: keep the session alive behind the TV. */
	public static synchronized void begin(EmulatorSession s, UUID id) {
		endNow(null);
		session = s;
		crtId = id;
		sinceNanos = System.nanoTime();
		pausedMode = ClientConfig.escMode() == ClientConfig.EscMode.PAUSE;
		s.releaseAll();
		s.turbo = false;
		s.setPaused(pausedMode);
	}

	public static synchronized boolean active() { return session != null; }

	public static synchronized UUID crtId() { return crtId; }

	/** The player is back at the TV: hand the live session to the new screen. Null if there is none for this TV. */
	public static synchronized EmulatorSession take(UUID id) {
		if (session == null || !id.equals(crtId) || session.isFinished()) return null;
		EmulatorSession s = session;
		session = null;
		crtId = null;
		s.setPaused(false);
		s.setAudioGain(1f);
		return s;
	}

	/** End the detached session now (save flushed, TV released). {@code why} is shown to the player when not null. */
	public static synchronized void endNow(String why) {
		EmulatorSession s = session;
		if (s == null) return;
		UUID id = crtId;
		session = null;
		crtId = null;
		s.setPaused(false);
		s.requestStop();
		try {
			ClientPlayNetworking.send(new Payloads.CrtClose(id));
		} catch (Throwable ignored) {
		}
		CrtClient.endOwn(id);
		GbaLinkClient.sessionEnded(s);
		if (why != null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui != null) mc.gui.hud.setOverlayMessage(Component.literal(why), false);
		}
	}

	/** Client tick: distance-faded sound while running, end when the session died or the time limit passed. */
	public static synchronized void tick(Minecraft mc) {
		EmulatorSession s = session;
		if (s == null) return;
		if (s.isFinished()) { endNow(null); return; }
		long limit = (pausedMode ? MAX_PAUSED_MINUTES : MAX_RUNNING_MINUTES) * 60_000_000_000L;
		if (System.nanoTime() - sinceNanos > limit) {
			endNow("The TV was released after " + (pausedMode ? MAX_PAUSED_MINUTES : MAX_RUNNING_MINUTES) + " minutes");
			return;
		}
		double[] c = CrtClient.screenCenter(crtId);
		if (mc.player != null && c != null) {
			double d = mc.player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(c[0], c[1], c[2]));
			s.setAudioGain(CrtAudio.attenuation(d));
		}
	}
}
