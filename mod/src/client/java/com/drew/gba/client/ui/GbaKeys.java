package com.drew.gba.client.ui;

import com.drew.gba.client.ControlMap;
import com.drew.gba.client.emu.EmulatorSession;
import com.mojang.blaze3d.platform.InputConstants;

/** The GBA key map shared by the bezel screen and the CRT control screen. */
public final class GbaKeys {
	// GBA KEYINPUT bit order
	public static final int A = 0, B = 1, SELECT = 2, START = 3, RIGHT = 4, LEFT = 5, UP = 6, DOWN = 7, R = 8, L = 9;

	private GbaKeys() {}

	/** The GBA button bit a key is bound to (see {@link ControlMap}, Controls screen), or -1. */
	public static int bitFor(int key) {
		return ControlMap.get().bitFor(key);
	}

	/** Display name of the key bound to an action ("F5", "Enter", "-" when unbound). */
	public static String keyName(ControlMap.Action a) {
		return keyName(ControlMap.get().primary(a));
	}

	public static String keyName(int key) {
		if (key == ControlMap.NONE) return "-";
		try {
			return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
		} catch (RuntimeException e) {
			return "Key " + key;
		}
	}

	/** Handles a key press; returns true if it was consumed. {@code powerOff} runs for the power key. */
	public static boolean press(EmulatorSession s, int key, Runnable powerOff) {
		ControlMap.Action a = ControlMap.get().actionFor(key);
		if (a == null) return false;
		switch (a) {
			case SAVE_STATE -> s.saveStateAsync();
			case LOAD_STATE -> s.loadStateAsync();
			case POWER -> powerOff.run();
			case TURBO -> s.turbo = true; // fast-forward while held
			default -> s.press(a.bit);
		}
		return true;
	}

	public static boolean release(EmulatorSession s, int key) {
		ControlMap.Action a = ControlMap.get().actionFor(key);
		if (a == null) return false;
		if (a == ControlMap.Action.TURBO) { s.turbo = false; return true; }
		if (a.bit >= 0) { s.release(a.bit); return true; }
		return false;
	}
}
