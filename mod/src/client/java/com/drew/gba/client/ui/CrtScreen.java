package com.drew.gba.client.ui;

import com.drew.gba.client.GbaLinkClient;
import com.drew.gba.client.crt.CrtClient;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import java.util.UUID;

/**
 * Control screen for playing on a CRT TV: transparent, never pauses, only captures the GBA keys. The picture
 * is drawn onto the TV in the world (see CrtClient). Esc leaves (suspends, like closing the GBA).
 */
public final class CrtScreen extends Screen {
	private static CrtScreen open;

	private final EmulatorSession session;
	private final UUID crtId;
	private boolean closed;
	private int turnTicks;
	private float startYaw, startPitch;
	private boolean turnInit;
	private boolean childOpen;
	private final HudHider hud = new HudHider();

	public CrtScreen(EmulatorSession session, UUID crtId) {
		super(Component.literal("CRT TV"));
		this.session = session;
		this.crtId = crtId;
		open = this;
	}

	/** The server ended our play (TV broken, walked away): leave the screen. */
	public static void serverEnded(UUID crtId) {
		CrtScreen s = open;
		if (s != null && s.crtId.equals(crtId) && s.minecraft != null) s.minecraft.execute(() -> {
			s.childOpen = false;
			if (s.minecraft.gui.screen() != s) { s.removed(); s.minecraft.gui.setScreen(null); } // an import screen is on top
			else s.onClose();
		});
	}

	@Override
	protected void init() {
		hud.hide(minecraft); // no crosshair, hand or HUD while playing; restored in removed()
	}

	/** E exports the cartridge's save to gbalink/exports, I opens the import picker (the game keeps running behind it). */
	private boolean menuKey(int key) {
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_E) {
			session.exportSaveAsync();
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_I) {
			session.releaseAll();
			session.turbo = false;
			childOpen = true;
			ImportConfirmScreen.begin(this, session);
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (menuKey(event.key())) return true;
		if (GbaKeys.press(session, event.key(), this::powerOff)) return true;
		return super.keyPressed(event); // Esc closes
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (GbaKeys.release(session, event.key())) return true;
		return super.keyReleased(event);
	}

	private void powerOff() {
		session.powerOff();
		onClose();
	}

	@Override
	public void tick() {
		session.setVolume(minecraft.options.getSoundSourceVolume(SoundSource.MASTER));
		if (!minecraft.isWindowActive() && System.getProperty("gbalink.autotest") == null) {
			session.releaseAll();
			session.turbo = false;
		}
		turnToScreen();
	}

	/** Eases the camera towards the middle of the TV screen over a few ticks. */
	private void turnToScreen() {
		var p = minecraft.player;
		double[] c = CrtClient.screenCenter(crtId);
		if (p == null || c == null) return;
		if (!turnInit) {
			turnInit = true;
			startYaw = p.getYRot();
			startPitch = p.getXRot();
		}
		if (turnTicks > 10) return;
		turnTicks++;
		var eye = p.getEyePosition();
		double dx = c[0] - eye.x, dy = c[1] - eye.y, dz = c[2] - eye.z;
		float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		float k = Math.min(1f, turnTicks / 8f);
		k = k * k * (3 - 2 * k);
		float dyaw = ((ty - startYaw) % 360f + 540f) % 360f - 180f;
		p.setYRot(startYaw + dyaw * k);
		p.setXRot(startPitch + (tp - startPitch) * k);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// transparent: the world stays fully visible
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		String line = session.error() != null ? session.error() : session.toast();
		double[] c = CrtClient.screenCenter(crtId);
		if (line.isEmpty() && c != null && minecraft.player != null) {
			double d = minecraft.player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(c[0], c[1], c[2]));
			if (d < 2.2) line = "Step back a little to see the whole picture";
		}
		boolean hint = line.isEmpty();
		int statsY = height - 23;
		if (hint) { // two short lines: the full list does not fit a normal window in one
			String l1 = "Arrows X Z A S Enter Backspace   Space: fast-forward   F5/F8: state";
			String l2 = "Esc: leave   E: export .sav   I: import .sav   P: power off";
			g.text(font, l1, (width - font.width(l1)) / 2, height - 22, 0x99FFFFFF, true);
			g.text(font, l2, (width - font.width(l2)) / 2, height - 12, 0x99FFFFFF, true);
			statsY = height - 33;
		} else {
			g.text(font, line, (width - font.width(line)) / 2, height - 12, session.error() != null ? 0xFFFF8888 : 0x99FFFFFF, true);
		}
		String stats = session.error() != null ? "" : session.linkStatsLine();
		if (!stats.isEmpty()) g.text(font, stats, (width - font.width(stats)) / 2, statsY, 0x80FFFFFF, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		if (childOpen) { childOpen = false; return; } // only switching to the import screens
		if (closed) return;
		closed = true;
		hud.restore(minecraft);
		if (open == this) open = null;
		session.requestStop();
		try {
			ClientPlayNetworking.send(new Payloads.CrtClose(crtId));
		} catch (Throwable ignored) {
		}
		CrtClient.endOwn(crtId);
		GbaLinkClient.sessionEnded(session);
	}
}
