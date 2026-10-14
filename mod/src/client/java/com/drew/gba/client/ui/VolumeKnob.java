package com.drew.gba.client.ui;

import com.drew.gba.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundSource;

/**
 * A volume thumb-wheel like the GBA's dial: a small ridged wheel in a slot with a label (icon + name) above it.
 * Drag horizontally or vertically (right / up is louder), or scroll over it. While hovered, dragged or just changed
 * by a key, the label shows the value. Two instances exist: the GBA volume (the player's own setting, see
 * {@link ClientConfig}) and Minecraft's master volume (the real Options setting, saved to options.txt).
 */
public final class VolumeKnob {
	enum Kind {
		GBA("gba", "GBA", "icon_speaker"),
		MC("mc", "MC", "icon_grass");

		final String sprite, label, icon;

		Kind(String sprite, String label, String icon) {
			this.sprite = sprite;
			this.label = label;
			this.icon = icon;
		}
	}

	static final int W = 40, WHEEL_H = 12, LABEL_H = 10, HEIGHT = LABEL_H + 1 + WHEEL_H;
	static final int STEP = 5;
	private static final int LABEL = 0xFFC9BDF2, READOUT = 0xFFFFE08A;
	private static final int PHASES = 8;
	private static final double TURNS = 2.5; // ridge pitches the wheel turns from 0% to 100%
	private static final double PERCENT_PER_PX = 1.5;

	final Kind kind;
	private int cx, top;
	private boolean dragging;
	private double acc;
	private long flashUntil;
	private long saveAt;          // a pending save (debounced while scrolling / pressing keys)
	private boolean dirty;
	private int debugX = Integer.MIN_VALUE, debugY; // dev autotest: pretend the pointer is here

	VolumeKnob(Kind kind) {
		this.kind = kind;
	}

	int percent() {
		if (kind == Kind.GBA) return ClientConfig.gbaVolume();
		return ClientConfig.clampPercent((int) Math.round(Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER) * 100));
	}

	/** Sets the volume (clamped). MC: applied live through Options (the sound engine hears it at once). */
	void set(int p) {
		p = ClientConfig.clampPercent(p);
		if (p == percent()) return;
		if (kind == Kind.GBA) ClientConfig.setGbaVolume(p);
		else Minecraft.getInstance().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(p / 100.0);
		dirty = true;
		saveAt = System.currentTimeMillis() + 500;
	}

	/** Step by a key press or scroll notch: also flashes the readout. */
	void change(int delta) {
		set(percent() + delta);
		flashUntil = System.currentTimeMillis() + 1400;
	}

	void layout(int centerX, int topY) {
		this.cx = centerX;
		this.top = topY;
	}

	public int centerX() { return cx; }
	int top() { return top; }
	public int wheelCenterY() { return top + LABEL_H + 1 + WHEEL_H / 2; }

	/** "GBA volume 80%" while the last key / scroll change is fresh, else "". */
	String freshText() {
		return System.currentTimeMillis() < flashUntil ? (kind == Kind.GBA ? "GBA" : "Minecraft") + " volume " + percent() + "%" : "";
	}

	/** Dev autotest only: make the knob think the pointer is at (x, y). */
	void debugPointer(int x, int y) {
		debugX = x;
		debugY = y;
	}

	boolean over(double mx, double my) {
		return mx >= cx - W / 2.0 - 2 && mx < cx + W / 2.0 + 2 && my >= top && my < top + HEIGHT;
	}

	boolean active(int mouseX, int mouseY) {
		return dragging || System.currentTimeMillis() < flashUntil || over(mouseX, mouseY)
			|| (debugX != Integer.MIN_VALUE && over(debugX, debugY));
	}

	void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		boolean active = active(mouseX, mouseY);
		int p = percent();
		String text = active ? p + "%" : kind.label;
		int tw = font.width(text), rowW = 10 + 3 + tw;
		int x0 = cx - rowW / 2;
		GuiSprites.sprite(g, kind.icon, x0 + 5, top + 5, 10, 10);
		g.text(font, text, x0 + 13, top + 1, active ? READOUT : LABEL, false);
		int phase = ((int) Math.floor(p / 100.0 * TURNS * PHASES) % PHASES + PHASES) % PHASES;
		GuiSprites.sprite(g, "wheel_" + kind.sprite + "_" + (PHASES - phase) % PHASES, cx, wheelCenterY(), W, WHEEL_H);
		if (active) { // a thin accent frame around the slot
			int y0 = top + LABEL_H + 1, c = 0xCCFFE08A;
			g.fill(cx - W / 2, y0, cx + W / 2, y0 + 1, c);
			g.fill(cx - W / 2, y0 + WHEEL_H - 1, cx + W / 2, y0 + WHEEL_H, c);
			g.fill(cx - W / 2, y0 + 1, cx - W / 2 + 1, y0 + WHEEL_H - 1, c);
			g.fill(cx + W / 2 - 1, y0 + 1, cx + W / 2, y0 + WHEEL_H - 1, c);
		}
	}

	// ------------------------------------------------------------------ input

	boolean mouseClicked(double mx, double my) {
		if (!over(mx, my)) return false;
		dragging = true;
		acc = 0;
		return true;
	}

	boolean mouseDragged(double dx, double dy) {
		if (!dragging) return false;
		acc += (dx - dy) * PERCENT_PER_PX;
		int whole = (int) acc;
		if (whole != 0) {
			set(percent() + whole);
			acc -= whole;
		}
		return true;
	}

	boolean mouseReleased() {
		if (!dragging) return false;
		dragging = false;
		flush();
		return true;
	}

	boolean mouseScrolled(double mx, double my, double scrollY) {
		if (!over(mx, my) || scrollY == 0) return false;
		change(scrollY > 0 ? STEP : -STEP);
		return true;
	}

	/** Writes a pending change to disk once it has settled. Call every tick. */
	void tick() {
		if (dirty && !dragging && System.currentTimeMillis() >= saveAt) flush();
	}

	void flush() {
		if (!dirty) return;
		dirty = false;
		if (kind == Kind.GBA) ClientConfig.save();
		else Minecraft.getInstance().options.save();
	}
}
