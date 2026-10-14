package com.drew.gba.client.ui;

import net.minecraft.client.Minecraft;

/**
 * Hides the whole HUD (crosshair, hand, hotbar, health, XP, ...) while a CRT is being played, exactly like F1, and puts
 * back whatever state the player had. The F1 state is a runtime flag of the HUD (it is not an option and is never
 * written to options.txt), so a crash can leave nothing behind; the CRT screen restores it in {@code removed()}.
 */
final class HudHider {
	private boolean active;
	private boolean wasHidden;

	void hide(Minecraft mc) {
		if (active || mc == null || mc.gui == null) return;
		active = true;
		wasHidden = mc.gui.hud.isHidden();
		if (!wasHidden) mc.gui.hud.toggle();
	}

	/** Back to the exact previous state (also if the player pressed F1 meanwhile). */
	void restore(Minecraft mc) {
		if (!active) return;
		active = false;
		if (mc != null && mc.gui != null && mc.gui.hud.isHidden() != wasHidden) mc.gui.hud.toggle();
	}

	boolean active() { return active; }
}
