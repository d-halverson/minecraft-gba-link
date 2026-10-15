package com.drew.gba.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * "You're linked with X. Leaving now disconnects ... Leave anyway?" - shown before anything that would leave the GBA / CRT
 * while linked. The emulator keeps running behind it (nothing is paused; netplay keeps pumping on its own thread). Stay is
 * the default (Esc = Stay); Leave returns to the session screen and runs {@code leave} there, so its normal close path
 * (suspend, HUD restore, volume restore) runs.
 */
public final class LeaveLinkScreen extends Screen {
	private final Screen parent;
	private final List<String> partners;
	private final Runnable leave;
	private Button stay;

	public LeaveLinkScreen(Screen parent, List<String> partners, Runnable leave) {
		super(Component.literal("Leave link"));
		this.parent = parent;
		this.partners = partners;
		this.leave = leave;
	}

	private List<String> lines() {
		return LeaveGuard.wrap(LeaveGuard.message(partners), Math.min(width - 40, 330), font::width);
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height / 2 - 10 + lines().size() * 6 + 14;
		stay = Button.builder(Component.literal("Stay"), b -> onClose()).bounds(cx - 102, y, 100, 20).build();
		addRenderableWidget(stay);
		addRenderableWidget(Button.builder(Component.literal("Leave"), b -> {
			if (minecraft.gui.screen() != this) return;
			minecraft.gui.setScreen(parent); // back on the session screen, which then closes the normal way
			leave.run();
		}).bounds(cx + 2, y, 100, 20).build());
		setInitialFocus(stay);
	}

	/** Esc and Stay: back to the game. */
	@Override
	public void onClose() {
		if (minecraft.gui.screen() == this) minecraft.gui.setScreen(parent); // (a stale popup must never re-set the parent)
	}

	/** Dev/test: the Leave button's centre. */
	public int[] leaveButtonCenter() {
		for (var c : children()) if (c instanceof Button b && b != stay) return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
		return null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		List<String> lines = lines();
		int y = height / 2 - 10 - lines.size() * 6;
		g.centeredText(font, "Leave the link?", width / 2, y - 16, 0xFFFFE08A);
		for (String l : lines) {
			g.centeredText(font, l, width / 2, y, 0xFFFFFFFF);
			y += 12;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
