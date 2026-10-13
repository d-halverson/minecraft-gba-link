package com.drew.gba.client.ui;

import com.drew.gba.client.emu.EmulatorSession;
import com.mojang.blaze3d.platform.InputConstants;

/** The GBA key map shared by the bezel screen and the CRT control screen. */
public final class GbaKeys {
	// GBA KEYINPUT bit order
	public static final int A = 0, B = 1, SELECT = 2, START = 3, RIGHT = 4, LEFT = 5, UP = 6, DOWN = 7, R = 8, L = 9;

	private GbaKeys() {}

	public static int bitFor(int key) {
		return switch (key) {
			case InputConstants.KEY_RIGHT -> RIGHT;
			case InputConstants.KEY_LEFT -> LEFT;
			case InputConstants.KEY_UP -> UP;
			case InputConstants.KEY_DOWN -> DOWN;
			case InputConstants.KEY_X -> A;
			case InputConstants.KEY_Z -> B;
			case InputConstants.KEY_A -> L;
			case InputConstants.KEY_S -> R;
			case InputConstants.KEY_RETURN, 88 /* keypad enter */ -> START;
			case InputConstants.KEY_BACKSPACE -> SELECT;
			default -> -1;
		};
	}

	/** Handles a key press; returns true if it was consumed. {@code powerOff} runs for the P key. */
	public static boolean press(EmulatorSession s, int key, Runnable powerOff) {
		int bit = bitFor(key);
		if (bit >= 0) { s.press(bit); return true; }
		if (key == InputConstants.KEY_F5) { s.saveStateAsync(); return true; }
		if (key == InputConstants.KEY_F8) { s.loadStateAsync(); return true; }
		if (key == InputConstants.KEY_P) { powerOff.run(); return true; }
		// Space (and Tab) = fast-forward while held
		if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_TAB) { s.turbo = true; return true; }
		return false;
	}

	public static boolean release(EmulatorSession s, int key) {
		int bit = bitFor(key);
		if (bit >= 0) { s.release(bit); return true; }
		if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_TAB) { s.turbo = false; return true; }
		return false;
	}
}
