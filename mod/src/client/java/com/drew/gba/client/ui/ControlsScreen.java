package com.drew.gba.client.ui;

import com.drew.gba.client.ControlMap;
import com.drew.gba.client.ControlMap.Action;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;

/**
 * The key map: every console button with its key; click one, press the new key (Esc cancels, Delete / Backspace is a
 * normal key, use the right mouse button on a row to clear it). A key that another button has is swapped over to it.
 * Done / Esc returns to the screen it was opened from (the game keeps running behind it).
 */
public final class ControlsScreen extends Screen {
	private static final Action[] ORDER = {
		Action.A, Action.B, Action.L, Action.R, Action.START, Action.SELECT, Action.UP,
		Action.DOWN, Action.LEFT, Action.RIGHT, Action.TURBO, Action.SAVE_STATE, Action.LOAD_STATE, Action.POWER};

	private final Screen parent;
	private final EnumMap<Action, Button> buttons = new EnumMap<>(Action.class);
	private Action waiting;
	private String note = "";
	private long noteUntil;

	public ControlsScreen(Screen parent) {
		super(Component.literal("Controls"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		buttons.clear();
		int rows = (ORDER.length + 1) / 2;
		int colW = Math.min(150, (width - 30) / 2);
		int labelW = colW * 55 / 100, keyW = colW - labelW - 4;
		int gap = 12, x0 = width / 2 - colW - gap / 2, x1 = width / 2 + gap / 2;
		int y0 = Math.max(34, height / 2 - (rows * 24 + 70) / 2 + 10);
		for (int i = 0; i < ORDER.length; i++) {
			Action a = ORDER[i];
			int col = i / rows, row = i % rows;
			int x = (col == 0 ? x0 : x1) + labelW + 4, y = y0 + row * 24;
			Button b = Button.builder(Component.literal(""), btn -> startWaiting(a)).bounds(x, y, keyW, 20).build();
			buttons.put(a, b);
			addRenderableWidget(b);
		}
		int by = y0 + rows * 24 + 10;
		addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> {
			waiting = null;
			ControlMap.get().resetDefaults();
			ControlMap.get().save();
			say("Defaults restored");
			refresh();
		}).bounds(width / 2 - 154, by, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 4, by, 150, 20).build());
		refresh();
	}

	private void startWaiting(Action a) {
		waiting = a;
		refresh();
	}

	private void say(String s) {
		note = s;
		noteUntil = System.currentTimeMillis() + 4000;
	}

	private void refresh() {
		for (var e : buttons.entrySet()) {
			Action a = e.getKey();
			String t = a == waiting ? "> press a key <" : GbaKeys.keyName(a);
			e.getValue().setMessage(Component.literal(t));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (waiting == null) return super.keyPressed(event); // Esc = Done
		int key = event.key();
		if (key == 41 /* Esc */) {
			waiting = null;
			say("Cancelled");
			refresh();
			return true;
		}
		if (!ControlMap.canBind(key)) {
			say(GbaKeys.keyName(key) + " is used by the console screens (volume / menu) and cannot be bound");
			return true;
		}
		Action a = waiting;
		waiting = null;
		ControlMap m = ControlMap.get();
		int old = m.primary(a);
		Action other = m.bind(a, key);
		m.save();
		if (other != null) {
			int now = m.primary(other);
			say(GbaKeys.keyName(key) + " was " + other.label + ": " + other.label + " is now " + (now == ControlMap.NONE ? "unbound" : GbaKeys.keyName(now)));
		} else {
			say(a.label + " = " + GbaKeys.keyName(key));
		}
		refresh();
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (waiting != null) { // any click while waiting cancels (a right click on a row clears it)
			Action target = waiting;
			waiting = null;
			if (event.button() == 1 && buttons.get(target) != null && buttons.get(target).isMouseOver(event.x(), event.y())) {
				ControlMap.get().clear(target);
				ControlMap.get().save();
				say(target.label + " cleared");
			} else {
				say("Cancelled");
			}
			refresh();
			return true;
		}
		if (event.button() == 1) { // right click on a key button clears it
			for (var e : buttons.entrySet()) {
				if (e.getValue().isMouseOver(event.x(), event.y())) {
					ControlMap.get().clear(e.getKey());
					ControlMap.get().save();
					say(e.getKey().label + " cleared");
					refresh();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** Dev/test: click the key button of an action and press a key (as the player would). */
	public void debugRebind(Action a, int key) {
		startWaiting(a);
		keyPressed(new KeyEvent(key, 0, 0));
	}

	/** Dev/test: begin waiting for a key for this action (for a screenshot). */
	public void debugWait(Action a) {
		startWaiting(a);
	}

	@Override
	public void onClose() {
		ControlMap.get().save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xE0080810);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int rows = (ORDER.length + 1) / 2;
		int colW = Math.min(150, (width - 30) / 2);
		int labelW = colW * 55 / 100;
		int gap = 12, x0 = width / 2 - colW - gap / 2, x1 = width / 2 + gap / 2;
		int y0 = Math.max(34, height / 2 - (rows * 24 + 70) / 2 + 10);
		g.centeredText(font, "Controls", width / 2, y0 - 26, 0xFFFFFFFF);
		g.centeredText(font, waiting == null ? "Click a key, then press the new one (Esc cancels, right-click clears)" : "Press the new key for " + waiting.label + "  (Esc cancels)",
			width / 2, y0 - 14, 0xFFC9BDF2);
		for (int i = 0; i < ORDER.length; i++) {
			int col = i / rows, row = i % rows;
			int x = col == 0 ? x0 : x1, y = y0 + row * 24 + 6;
			g.text(font, ORDER[i].label, x, y, ORDER[i] == waiting ? 0xFFFFE08A : 0xFFE0D8FF, false);
		}
		int by = y0 + rows * 24 + 10;
		if (!note.isEmpty() && System.currentTimeMillis() < noteUntil) {
			g.centeredText(font, note, width / 2, by + 28, 0xFFFFFFAA);
		}
		g.centeredText(font, "Saved to gbalink/controls.properties. Game Boy games use the same keys (A, B, Select, Start, D-pad).", width / 2, by + 42, 0xFF9C92C4);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
