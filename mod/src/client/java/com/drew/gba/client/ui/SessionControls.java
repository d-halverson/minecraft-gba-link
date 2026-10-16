package com.drew.gba.client.ui;

import com.drew.gba.client.ControlMap;
import com.drew.gba.client.emu.EmulatorSession;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The console controls shared by the GBA screen and the CRT options panel: the Save state / Load state / Export .sav /
 * Import .sav / Close buttons, the power switch with its LED, and the two volume wheels (GBA and Minecraft). The
 * screens lay the pieces out; this class owns the behaviour, so both behave identically (including the netplay
 * restrictions, which live in {@link EmulatorSession}).
 *
 * Volume keys (while a screen with these controls is open): {@code -} / {@code =} for the GBA volume, {@code [} /
 * {@code ]} for Minecraft's. None of them is a GBA key (see {@link GbaKeys}).
 */
public final class SessionControls {
	/** What the owning screen does for the controls. */
	interface Host {
		/** The screen to return to after the import picker. */
		Screen screen();
		/** The screen is about to be replaced by the import screens: do not suspend the game when it is removed. */
		void importStarting();
		/** Power off (switch / P key), after the leave confirmation: {@link #requestPowerOff} runs this once confirmed. */
		void powerOffNow();
		/** Close the screen for real (suspends), skipping the confirmation. */
		void closeNow();
	}

	static final int SW_W = 40, SW_H = 14;
	private static final int KEYPAD_MINUS = 86; // no InputConstants name for it
	private static final int LABEL = 0xFFC9BDF2;

	final EmulatorSession session;
	public final VolumeKnob gba = new VolumeKnob(VolumeKnob.Kind.GBA);
	public final VolumeKnob mc = new VolumeKnob(VolumeKnob.Kind.MC);
	private final Host host;
	private int swCx, swCy; // power switch centre (GUI px); 0 = not shown
	private int debugX = Integer.MIN_VALUE, debugY;

	SessionControls(EmulatorSession session, Host host) {
		this.session = session;
		this.host = host;
		beginSession();
	}

	// ------------------------------------------------------------------ buttons

	Button save(int x, int y, int w, int h) {
		return Button.builder(Component.literal("Save (" + GbaKeys.keyName(ControlMap.Action.SAVE_STATE) + ")"), b -> session.saveStateAsync()).bounds(x, y, w, h).build();
	}

	Button load(int x, int y, int w, int h) {
		return Button.builder(Component.literal("Load (" + GbaKeys.keyName(ControlMap.Action.LOAD_STATE) + ")"), b -> session.loadStateAsync()).bounds(x, y, w, h).build();
	}

	Button export(int x, int y, int w, int h) {
		return Button.builder(Component.literal("Export .sav"), b -> session.exportSaveAsync()).bounds(x, y, w, h).build();
	}

	Button importSav(int x, int y, int w, int h) {
		return Button.builder(Component.literal("Import .sav"), b -> openImport()).bounds(x, y, w, h).build();
	}

	/** "Controls": the key map screen, opened over this one; the game keeps running behind it. */
	Button controls(int x, int y, int w, int h) {
		return Button.builder(Component.literal("Controls"), b -> openControls()).bounds(x, y, w, h).build();
	}

	void openControls() {
		session.releaseAll(); // the Controls screen swallows the key releases
		session.turbo = false;
		host.importStarting(); // a child screen opens over ours: removed() must not suspend the game
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new ControlsScreen(host.screen()));
	}

	String powerLabel() {
		return "POWER OFF (" + GbaKeys.keyName(ControlMap.Action.POWER) + ")";
	}

	/** "Unlink (U)": take yourself off the link cable. Callers show it only while {@link com.drew.gba.client.ClientCable#linked()}. */
	Button unlink(int x, int y, int w, int h) {
		return Button.builder(Component.literal("Unlink (U)"), b -> com.drew.gba.client.ClientCable.requestUnlink(net.minecraft.client.Minecraft.getInstance()))
			.bounds(x, y, w, h).build();
	}

	Button leave(String label, int x, int y, int w, int h) {
		return Button.builder(Component.literal(label), b -> requestLeave(host::closeNow)).bounds(x, y, w, h).build();
	}

	/** Import flow: picker -> confirmation -> the session restarts from the imported save. The game keeps running behind. */
	void openImport() {
		if (session.isFinished() || session.importBlocked()) return; // netplay: toast and no picker
		session.releaseAll();
		session.turbo = false;
		host.importStarting();
		ImportConfirmScreen.begin(host.screen(), session);
	}

	// ------------------------------------------------------------------ leaving

	/**
	 * Runs {@code doLeave} at once when not linked; while linked first asks "Leave anyway?" (the game keeps running
	 * behind the popup). Every way out of the session screen goes through here.
	 */
	void requestLeave(Runnable doLeave) {
		if (!session.linkActive()) {
			doLeave.run();
			return;
		}
		session.releaseAll(); // the popup swallows the key releases
		session.turbo = false;
		host.importStarting(); // a child screen opens over ours: removed() must not suspend the game
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new LeaveLinkScreen(host.screen(), session.linkPartners(), doLeave));
	}

	/** Power switch or P key. */
	void requestPowerOff() {
		requestLeave(host::powerOffNow);
	}

	// ------------------------------------------------------------------ power switch

	/** Draws the switch centred on (cx, cy) with its LED to the right; {@code hoverLabel} appears below it while hovered. */
	void drawPowerSwitch(GuiGraphicsExtractor g, Font font, int cx, int cy, int mouseX, int mouseY, boolean hoverLabel) {
		swCx = cx;
		swCy = cy;
		boolean hover = overSwitch(pointerX(mouseX, mouseY), pointerY(mouseX, mouseY));
		GuiSprites.sprite(g, hover ? "power_off" : "power_on", cx, cy, SW_W, SW_H);
		boolean ok = session.error() == null && !session.isFinished();
		GuiSprites.sprite(g, ok ? "led_green" : "led_red", cx + SW_W / 2 + 8, cy, 8, 8);
		if (hover && hoverLabel) {
			String t = powerLabel();
			g.text(font, t, cx - font.width(t) / 2, cy + SW_H / 2 + 3, 0xFFFFE08A, false);
		}
	}

	void hideSwitch() {
		swCx = 0;
	}

	boolean overSwitch(double mx, double my) {
		return swCx > 0 && Math.abs(mx - swCx) <= SW_W / 2.0 + 1 && Math.abs(my - swCy) <= SW_H / 2.0 + 1;
	}

	/** Dev/test: centre of the power switch in GUI px (valid after the first render), or null. */
	public int[] powerSwitchCenter() {
		return swCx > 0 ? new int[] {swCx, swCy} : null;
	}

	private double pointerX(int mouseX, int mouseY) { return debugX != Integer.MIN_VALUE ? debugX : mouseX; }
	private double pointerY(int mouseX, int mouseY) { return debugX != Integer.MIN_VALUE ? debugY : mouseY; }

	/** Dev autotest only: pretend the pointer is at (x, y) for hover effects; null args via {@link #clearDebugPointer()}. */
	public void debugPointer(int x, int y) {
		debugX = x;
		debugY = y;
		gba.debugPointer(x, y);
		mc.debugPointer(x, y);
	}

	// ------------------------------------------------------------------ knobs

	void renderKnobs(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		int mx = (int) pointerX(mouseX, mouseY), my = (int) pointerY(mouseX, mouseY);
		gba.hideLabel = mc.active(mx, my); // MC's "(while playing)" readout is wider than its wheel and covers the GBA label
		gba.render(g, font, mx, my);
		mc.render(g, font, mx, my);
	}

	// ------------------------------------------------------------------ input (each returns true if consumed)

	boolean mouseClicked(MouseButtonEvent e) {
		if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (overSwitch(e.x(), e.y())) {
			requestPowerOff();
			return true;
		}
		return gba.mouseClicked(e.x(), e.y()) || mc.mouseClicked(e.x(), e.y());
	}

	boolean mouseDragged(double dx, double dy) {
		return gba.mouseDragged(dx, dy) || mc.mouseDragged(dx, dy);
	}

	boolean mouseReleased() {
		boolean a = gba.mouseReleased(), b = mc.mouseReleased();
		return a || b;
	}

	boolean mouseScrolled(double mx, double my, double sy) {
		return gba.mouseScrolled(mx, my, sy) || mc.mouseScrolled(mx, my, sy);
	}

	/** {@code -} {@code =} (GBA) and {@code [} {@code ]} (Minecraft); keypad plus/minus too. Returns true if consumed. */
	/** The Unlink key (default U, rebindable under Controls > GBA Link). */
	boolean unlinkKey(net.minecraft.client.input.KeyEvent e) {
		var k = com.drew.gba.client.GbaLinkClient.unlinkKey;
		if (k == null || !k.matches(e)) return false;
		com.drew.gba.client.ClientCable.requestUnlink(net.minecraft.client.Minecraft.getInstance());
		return true;
	}

	boolean volumeKey(int key) {
		switch (key) {
			case InputConstants.KEY_MINUS, KEYPAD_MINUS -> gba.change(-VolumeKnob.STEP);
			case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> gba.change(VolumeKnob.STEP);
			case InputConstants.KEY_LBRACKET -> mc.change(-VolumeKnob.STEP);
			case InputConstants.KEY_RBRACKET -> mc.change(VolumeKnob.STEP);
			default -> { return false; }
		}
		return true;
	}

	/** "GBA volume 80%" for a second after a key / scroll change (for screens where the wheels may be hidden), else "". */
	String volumeNotice() {
		String t = gba.freshText();
		return t.isEmpty() ? mc.freshText() : t;
	}

	void tick() {
		gba.tick();
		mc.tick();
	}

	/** Write anything pending (screen closing). */
	void flush() {
		gba.flush();
		mc.flush();
	}

	// ------------------------------------------------------------------ Minecraft volume: restored when the session screen closes

	/** Snapshots Minecraft's master volume (the first session screen to open). Pair with {@link #endSession}. */
	void beginSession() {
		MC_VOLUME.begin(this);
	}

	/** The session screen is really closing: put Minecraft's master volume back and save options.txt. */
	void endSession() {
		MC_VOLUME.end(this);
	}

	/** Client stopping: restore if a session screen was still open. */
	public static void restoreMcVolumeNow() {
		MC_VOLUME.endAll();
	}

	private static final VolumeRestore MC_VOLUME = new VolumeRestore(
		() -> net.minecraft.client.Minecraft.getInstance().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).get(),
		v -> net.minecraft.client.Minecraft.getInstance().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(v),
		() -> net.minecraft.client.Minecraft.getInstance().options.save());
}
