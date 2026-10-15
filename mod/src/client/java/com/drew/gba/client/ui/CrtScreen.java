package com.drew.gba.client.ui;

import com.drew.gba.client.ClientCable;
import com.drew.gba.client.GbaLinkClient;
import com.drew.gba.client.crt.CrtClient;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Control screen for playing on a CRT TV: transparent, never pauses, only captures the GBA keys. The picture
 * is drawn onto the TV in the world (see CrtClient). Esc leaves (suspends, like closing the GBA).
 *
 * A small "Options (O)" button in the top-right corner shows or hides a panel with the same controls as the GBA
 * screen (save/load state, export/import .sav, power switch, both volume wheels, leave); see {@link SessionControls}.
 * Whether the panel was open is remembered for the rest of the game session.
 */
public final class CrtScreen extends Screen {
	private static CrtScreen open;
	private static boolean panelOpen; // remembered across plays until the game is closed

	private static final int PANEL_W = 122, PANEL_PAD = 9, BTN_H = 16, BTN_GAP = 3;
	private static final int ACCENT = 0xFF7A68D8, PANEL_BG = 0xC4100E22, PANEL_TEXT = 0xFFC9BDF2;

	private final EmulatorSession session;
	private final UUID crtId;
	private boolean closed;
	private int turnTicks;
	private float startYaw, startPitch;
	private boolean turnInit;
	private boolean childOpen;
	private int sentVolume = -1;
	private boolean builtLinked;
	private final HudHider hud = new HudHider();
	private final SessionControls controls;
	private final List<AbstractWidget> panelWidgets = new ArrayList<>();
	private Button optionsButton;
	private int panelX, panelY, panelH;
	private int switchCx, switchCy;

	public CrtScreen(EmulatorSession session, UUID crtId) {
		super(Component.literal("CRT TV"));
		this.session = session;
		this.crtId = crtId;
		this.controls = new SessionControls(session, new SessionControls.Host() {
			@Override public Screen screen() { return CrtScreen.this; }
			@Override public void importStarting() { childOpen = true; } // the import screens return here; removed() must not leave the TV
			@Override public void powerOffNow() { session.powerOff(); closeNow(); }
			@Override public void closeNow() { CrtScreen.this.closeNow(); }
		});
		open = this;
	}

	/** The server ended our play (TV broken, walked away): leave the screen. */
	public static void serverEnded(UUID crtId) {
		CrtScreen s = open;
		if (s != null && s.crtId.equals(crtId) && s.minecraft != null) s.minecraft.execute(() -> {
			s.childOpen = false;
			if (s.minecraft.gui.screen() != s) { s.removed(); s.minecraft.gui.setScreen(null); } // an import screen is on top
			else s.closeNow(); // forced: no confirmation
		});
	}

	@Override
	protected void init() {
		hud.hide(minecraft); // no crosshair, hand or HUD while playing; restored in removed()
		panelWidgets.clear();
		optionsButton = Button.builder(Component.literal("Options (O)"), b -> togglePanel())
			.bounds(width - 70, 4, 66, 16).build();
		addRenderableWidget(optionsButton);
		// the panel: a column of buttons, the power switch and the two volume wheels, under the Options button
		panelX = width - PANEL_W - 4;
		panelY = 24;
		int bx = panelX + PANEL_PAD, bw = PANEL_W - 2 * PANEL_PAD, y = panelY + 8;
		panelWidgets.add(controls.save(bx, y, bw, BTN_H));
		panelWidgets.add(controls.load(bx, y += BTN_H + BTN_GAP, bw, BTN_H));
		panelWidgets.add(controls.export(bx, y += BTN_H + BTN_GAP, bw, BTN_H));
		panelWidgets.add(controls.importSav(bx, y += BTN_H + BTN_GAP, bw, BTN_H));
		builtLinked = ClientCable.linked();
		if (builtLinked) panelWidgets.add(controls.unlink(bx, y += BTN_H + BTN_GAP, bw, BTN_H)); // only while cabled
		panelWidgets.add(controls.leave("Leave (Esc)", bx, y += BTN_H + BTN_GAP, bw, BTN_H));
		y += BTN_H + 9;
		switchCx = panelX + PANEL_PAD + 4 + SessionControls.SW_W / 2;
		switchCy = y + SessionControls.SW_H / 2;
		y += SessionControls.SW_H + 8;
		controls.gba.layout(panelX + PANEL_W / 4 + 1, y);
		controls.mc.layout(panelX + PANEL_W * 3 / 4 - 1, y);
		panelH = y + VolumeKnob.HEIGHT + 8 - panelY;
		for (AbstractWidget w : panelWidgets) addRenderableWidget(w);
		applyPanelVisibility();
	}

	private void togglePanel() {
		panelOpen = !panelOpen;
		applyPanelVisibility();
	}

	private void applyPanelVisibility() {
		for (AbstractWidget w : panelWidgets) {
			w.visible = panelOpen;
			w.active = panelOpen;
		}
		if (!panelOpen) setFocused(null);
	}

	/** Dev/test: whether the options panel is showing. */
	public boolean panelOpen() { return panelOpen; }

	/** Dev/test: show or hide the options panel. */
	public void setPanelOpen(boolean open) {
		panelOpen = open;
		applyPanelVisibility();
	}

	/** Dev/test: the knobs' controller. */
	public SessionControls controls() { return controls; }

	/** Dev/test: centre of the Options button in GUI px. */
	public int[] optionsButtonCenter() { return new int[] {optionsButton.getX() + optionsButton.getWidth() / 2, optionsButton.getY() + optionsButton.getHeight() / 2}; }

	/** E exports the cartridge's save to gbalink/exports, I opens the import picker (the game keeps running behind it). */
	private boolean menuKey(int key) {
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_E) {
			session.exportSaveAsync();
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_I) {
			controls.openImport();
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_O) {
			togglePanel();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (menuKey(event.key())) return true;
		if (GbaKeys.press(session, event.key(), controls::requestPowerOff)) return true;
		if (controls.volumeKey(event.key()) || controls.unlinkKey(event)) return true;
		return super.keyPressed(event); // Esc closes
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (panelOpen && controls.mouseClicked(event)) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
		if (panelOpen && controls.mouseDragged(dx, dy)) return true;
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (controls.mouseReleased()) return true;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (panelOpen && controls.mouseScrolled(x, y, scrollY)) return true;
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (GbaKeys.release(session, event.key())) return true;
		return super.keyReleased(event);
	}

	/** Esc / Leave: asks first while linked (see {@link SessionControls#requestLeave}). */
	@Override
	public void onClose() {
		controls.requestLeave(this::closeNow);
	}

	private void closeNow() {
		super.onClose();
	}

	@Override
	public void tick() {
		controls.tick();
		if (ClientCable.linked() != builtLinked) rebuildWidgets();
		if (sentVolume != com.drew.gba.client.ClientConfig.gbaVolume()) { // the TV volume = this player's GBA volume; everyone who hears the TV uses it
			sentVolume = com.drew.gba.client.ClientConfig.gbaVolume();
			try {
				ClientPlayNetworking.send(new Payloads.CrtVolume(crtId, sentVolume));
			} catch (Throwable ignored) {
			}
		}
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
		if (line.isEmpty() && !panelOpen) line = controls.volumeNotice(); // a key changed a volume while the wheels are hidden
		double[] c = CrtClient.screenCenter(crtId);
		if (line.isEmpty() && c != null && minecraft.player != null) {
			double d = minecraft.player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(c[0], c[1], c[2]));
			if (d < 2.2) line = "Step back a little to see the whole picture";
		}
		boolean hint = line.isEmpty();
		int statsY = height - 23;
		if (hint) { // two short lines: the full list does not fit a normal window in one
			String l1 = "Arrows X Z A S Enter Backspace   Space: fast-forward   F5/F8: state";
			String l2 = "Esc: leave   O: options   E: export .sav   I: import .sav   P: power off";
			g.text(font, l1, (width - font.width(l1)) / 2, height - 22, 0x99FFFFFF, true);
			g.text(font, l2, (width - font.width(l2)) / 2, height - 12, 0x99FFFFFF, true);
			statsY = height - 33;
		} else {
			g.text(font, line, (width - font.width(line)) / 2, height - 12, session.error() != null ? 0xFFFF8888 : 0x99FFFFFF, true);
		}
		String stats = session.error() != null ? "" : session.linkStatsLine();
		if (!stats.isEmpty()) g.text(font, stats, (width - font.width(stats)) / 2, statsY, 0x80FFFFFF, true);
		if (panelOpen) drawPanelBackground(g);
		super.extractRenderState(g, mouseX, mouseY, a); // the Options button and the panel's buttons
		if (panelOpen) {
			controls.drawPowerSwitch(g, font, switchCx, switchCy, mouseX, mouseY, false);
			g.text(font, controls.overSwitch(mouseX, mouseY) ? "Power off (P)" : "Power (P)", switchCx + SessionControls.SW_W / 2 + 17, switchCy - 4,
				controls.overSwitch(mouseX, mouseY) ? 0xFFFFE08A : PANEL_TEXT, false);
			controls.renderKnobs(g, font, mouseX, mouseY);
		}
	}

	/** Semi-transparent dark panel with an indigo accent edge. */
	private void drawPanelBackground(GuiGraphicsExtractor g) {
		int x0 = panelX, y0 = panelY - 2, x1 = panelX + PANEL_W, y1 = panelY + panelH;
		g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0x66000000); // soft outer shadow
		g.fill(x0, y0, x1, y1, PANEL_BG);
		g.fill(x0, y0, x1, y0 + 2, ACCENT);                 // indigo accent bar
		g.fill(x0, y0 + 2, x0 + 1, y1, 0x805A49B0);          // hairline edges
		g.fill(x1 - 1, y0 + 2, x1, y1, 0x805A49B0);
		g.fill(x0, y1 - 1, x1, y1, 0x805A49B0);
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
		controls.flush();
		controls.endSession(); // Minecraft's volume back to what it was
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
