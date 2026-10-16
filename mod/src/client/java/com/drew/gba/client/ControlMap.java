package com.drew.gba.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * The keyboard map for the console: which key does what. No Minecraft classes (key codes are the plain ints that
 * {@code KeyEvent.key()} gives), so it is unit-testable. Kept in {@code <gameDir>/gbalink/controls.properties}; a missing,
 * unreadable or corrupt file means the defaults. A key is bound to at most one action: binding a key that another
 * action has takes it from that action (which gets this action's old key, a swap, if it had one).
 */
public final class ControlMap {
	public static final String FILE_NAME = "controls.properties";
	/** "no key". */
	public static final int NONE = 0;

	// Key codes (same values as InputConstants.KEY_*; written out so this class stays free of Minecraft classes).
	static final int K_A = 4, K_P = 19, K_S = 22, K_X = 27, K_Z = 29, K_RETURN = 40, K_ESCAPE = 41, K_BACKSPACE = 42, K_TAB = 43,
		K_SPACE = 44, K_MINUS = 45, K_EQUALS = 46, K_LBRACKET = 47, K_RBRACKET = 48, K_F5 = 62, K_F8 = 65,
		K_RIGHT = 79, K_LEFT = 80, K_DOWN = 81, K_UP = 82, K_NUMPADENTER = 88, K_E = 8, K_I = 12, K_O = 18,
		K_KP_MINUS = 86, K_KP_PLUS = 87;

	/** Everything a key can do. The first eight (and the order of the GBA bits) match {@code GbaKeys}. */
	public enum Action {
		A("A", 0), B("B", 1), SELECT("Select", 2), START("Start", 3), RIGHT("Right", 4), LEFT("Left", 5), UP("Up", 6), DOWN("Down", 7),
		R("R", 8), L("L", 9),
		TURBO("Fast-forward", -1), SAVE_STATE("Save state", -1), LOAD_STATE("Load state", -1), POWER("Power off", -1);

		public final String label;
		/** GBA KEYINPUT bit, or -1 for the non-button actions. */
		public final int bit;

		Action(String label, int bit) {
			this.label = label;
			this.bit = bit;
		}
	}

	/** Keys the console screens use for something else: they cannot be bound. */
	public static boolean reserved(int key) {
		return switch (key) {
			case K_ESCAPE, K_MINUS, K_EQUALS, K_LBRACKET, K_RBRACKET, K_KP_MINUS, K_KP_PLUS, K_E, K_I, K_O -> true; // Esc, volume keys, CRT menu keys
			default -> false;
		};
	}

	/** Default keys for an action: the first is the one shown; the rest are aliases that go away once the action is rebound. */
	static int[] defaults(Action a) {
		return switch (a) {
			case A -> new int[] {K_X};
			case B -> new int[] {K_Z};
			case L -> new int[] {K_A};
			case R -> new int[] {K_S};
			case START -> new int[] {K_RETURN, K_NUMPADENTER};
			case SELECT -> new int[] {K_BACKSPACE};
			case UP -> new int[] {K_UP};
			case DOWN -> new int[] {K_DOWN};
			case LEFT -> new int[] {K_LEFT};
			case RIGHT -> new int[] {K_RIGHT};
			case TURBO -> new int[] {K_SPACE, K_TAB};
			case SAVE_STATE -> new int[] {K_F5};
			case LOAD_STATE -> new int[] {K_F8};
			case POWER -> new int[] {K_P};
		};
	}

	private final EnumMap<Action, int[]> keys = new EnumMap<>(Action.class);
	private Path file;

	public ControlMap() {
		resetDefaults();
	}

	/** The map the screens use; replaced by {@link #init}. */
	private static volatile ControlMap current = new ControlMap();

	public static ControlMap get() {
		return current;
	}

	/** Loads {@code <baseDir>/controls.properties} (created on the first save) as the map in use. Never throws. */
	public static void init(Path baseDir) {
		ControlMap m = new ControlMap();
		m.file = baseDir.resolve(FILE_NAME);
		m.load();
		current = m;
	}

	public synchronized void resetDefaults() {
		for (Action a : Action.values()) keys.put(a, defaults(a));
	}

	// ------------------------------------------------------------------ queries

	/** The action a key triggers, or null. */
	public synchronized Action actionFor(int key) {
		if (key == NONE) return null;
		for (Map.Entry<Action, int[]> e : keys.entrySet()) {
			for (int k : e.getValue()) if (k == key) return e.getKey();
		}
		return null;
	}

	/** GBA KEYINPUT bit for a key, or -1 (not a console button). */
	public int bitFor(int key) {
		Action a = actionFor(key);
		return a == null ? -1 : a.bit;
	}

	/** The key shown for an action, or {@link #NONE}. */
	public synchronized int primary(Action a) {
		int[] k = keys.get(a);
		return k.length == 0 ? NONE : k[0];
	}

	public synchronized boolean isDefault() {
		for (Action a : Action.values()) if (!java.util.Arrays.equals(keys.get(a), defaults(a))) return false;
		return true;
	}

	// ------------------------------------------------------------------ changes

	/**
	 * Binds {@code key} to {@code a} (replacing all the action's keys). If another action had the key it loses it and gets
	 * this action's old key instead (a swap), or is left unbound if this action had none. Callers check {@link #canBind} first.
	 *
	 * @return the other action that lost the key, or null
	 */
	public synchronized Action bind(Action a, int key) {
		if (key == NONE) { clear(a); return null; }
		int old = primary(a);
		Action other = actionFor(key);
		keys.put(a, new int[] {key});
		if (other == null || other == a) return null;
		int[] rest = java.util.Arrays.stream(keys.get(other)).filter(k -> k != key).toArray();
		if (rest.length == 0 && old != NONE) rest = new int[] {old};
		keys.put(other, rest);
		return other;
	}

	public synchronized void clear(Action a) {
		keys.put(a, new int[0]);
	}

	private static boolean isDefaultKey(Action a, int k) {
		for (int d : defaults(a)) if (d == k) return true;
		return false;
	}

	/** False for keys the console screens keep for themselves (Esc, volume keys, E / I / O). */
	public static boolean canBind(int key) {
		return key != NONE && !reserved(key);
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void load() {
		resetDefaults();
		if (file == null || !Files.isRegularFile(file)) return;
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		} catch (IOException | IllegalArgumentException e) {
			return;
		}
		EnumMap<Action, int[]> loaded = new EnumMap<>(Action.class);
		java.util.HashSet<Integer> used = new java.util.HashSet<>();
		for (Action a : Action.values()) {
			String s = p.getProperty(a.name());
			if (s == null) { loaded.put(a, defaults(a)); continue; }
			s = s.trim();
			if (s.isEmpty()) { loaded.put(a, new int[0]); continue; } // explicitly unbound
			try {
				String[] parts = s.split(",");
				int[] ks = new int[parts.length];
				for (int i = 0; i < parts.length; i++) {
					int k = Integer.parseInt(parts[i].trim());
					if (!canBind(k) && !isDefaultKey(a, k)) throw new NumberFormatException();
					if (k < 1 || k > 0xFFFF) throw new NumberFormatException();
					ks[i] = k;
				}
				loaded.put(a, ks);
			} catch (NumberFormatException e) {
				loaded.put(a, defaults(a)); // corrupt value: this action's default
			}
		}
		for (int[] ks : loaded.values()) {
			for (int k : ks) {
				if (!used.add(k)) return; // the same key twice: the file is inconsistent, keep the defaults
			}
		}
		keys.putAll(loaded);
	}

	/** Writes the file. Never throws (a read-only folder just means the keys are not remembered). */
	public synchronized void save() {
		Path f = file;
		if (f == null) return;
		Properties p = new Properties();
		for (Action a : Action.values()) {
			StringBuilder sb = new StringBuilder();
			for (int k : keys.get(a)) sb.append(sb.length() == 0 ? "" : ",").append(k);
			p.setProperty(a.name(), sb.toString());
		}
		try {
			Files.createDirectories(f.getParent());
			try (OutputStream out = Files.newOutputStream(f)) {
				p.store(out, "gbalink key map: action = key code (the Controls screen writes this; delete the file to reset). Empty = unbound");
			}
		} catch (IOException ignored) {
		}
	}
}
