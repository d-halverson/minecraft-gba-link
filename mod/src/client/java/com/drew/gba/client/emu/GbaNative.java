package com.drew.gba.client.emu;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Foreign Function &amp; Memory binding for libgbacore (see gba/SPEC.md, gbacore.h). The library is
 * extracted from the mod jar at natives/&lt;os&gt;-&lt;arch&gt;/&lt;lib&gt;. All handles are looked up once.
 */
public final class GbaNative {
	public static final int WIDTH = 240, HEIGHT = 160, AUDIO_RATE = 32768, LINK_MSG_MAX = 64;
	public static final int RUN_FRAME_DONE = 0, RUN_LINK_WAIT = 1, RUN_ERROR = -1;
	public static final int API_VERSION = 3, MIN_API_VERSION = 1, RTC_WALL = 0, RTC_VIRTUAL = 1;

	private static GbaNative instance;
	private static String loadError;

	final MethodHandle apiVersion, create, destroy, loadRom, reset, romInfo, setKeys, runFrame, framebuffer, readAudio;
	final MethodHandle saveSize, getSave, loadSave, saveDirty, stateSize, saveState, loadState;
	final MethodHandle linkConfigure, linkPollOutgoing, linkDeliver;
	/** gba_set_rtc (API 2); null for a version 1 library. */
	final MethodHandle setRtc;
	/** gba_group_* (API 3); null for older libraries. */
	final MethodHandle groupCreate, groupAdd, groupRunFrame, groupDestroy;
	final int version;

	private GbaNative(SymbolLookup lib, int version) {
		this.version = version;
		Linker l = Linker.nativeLinker();
		apiVersion = h(l, lib, "gba_api_version", FunctionDescriptor.of(JAVA_INT));
		create = h(l, lib, "gba_create", FunctionDescriptor.of(ADDRESS));
		destroy = h(l, lib, "gba_destroy", FunctionDescriptor.ofVoid(ADDRESS));
		loadRom = h(l, lib, "gba_load_rom", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		reset = h(l, lib, "gba_reset", FunctionDescriptor.ofVoid(ADDRESS));
		romInfo = h(l, lib, "gba_rom_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		setKeys = h(l, lib, "gba_set_keys", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
		runFrame = h(l, lib, "gba_run_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		framebuffer = h(l, lib, "gba_framebuffer", FunctionDescriptor.of(ADDRESS, ADDRESS));
		readAudio = h(l, lib, "gba_read_audio", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
		saveSize = h(l, lib, "gba_save_size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
		getSave = h(l, lib, "gba_get_save", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
		loadSave = h(l, lib, "gba_load_save", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
		saveDirty = h(l, lib, "gba_save_dirty", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		stateSize = h(l, lib, "gba_state_size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
		saveState = h(l, lib, "gba_save_state", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
		loadState = h(l, lib, "gba_load_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
		linkConfigure = h(l, lib, "gba_link_configure", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
		linkPollOutgoing = h(l, lib, "gba_link_poll_outgoing", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
		linkDeliver = h(l, lib, "gba_link_deliver", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
		groupCreate = version >= 3 ? h(l, lib, "gba_group_create", FunctionDescriptor.of(ADDRESS, JAVA_INT)) : null;
		groupAdd = version >= 3 ? h(l, lib, "gba_group_add", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) : null;
		groupRunFrame = version >= 3 ? h(l, lib, "gba_group_run_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS)) : null;
		groupDestroy = version >= 3 ? h(l, lib, "gba_group_destroy", FunctionDescriptor.ofVoid(ADDRESS)) : null;
		setRtc = version >= 2 ? h(l, lib, "gba_set_rtc", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)) : null;
	}

	/** True if the library has the virtual RTC and lockstep groups (API 3), which input-sync netplay needs. */
	public boolean supportsNetplay() { return setRtc != null && groupCreate != null; }

	private static MethodHandle h(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
		MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("missing symbol " + name));
		return l.downcallHandle(sym, fd);
	}

	// ------------------------------------------------------------------ loading

	public static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String o = os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("win") ? "windows" : "linux";
		String a = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";
		return o + "-" + a;
	}

	private static String libName(String platform) {
		if (platform.startsWith("macos")) return "libgbacore.dylib";
		if (platform.startsWith("windows")) return "gbacore.dll";
		return "libgbacore.so";
	}

	/** Load (once). Returns null and records {@link #loadError()} if the library is unavailable. */
	public static synchronized GbaNative get(Path gameDir) {
		if (instance != null) return instance;
		if (loadError != null) return null;
		try {
			String platform = platform();
			String name = libName(platform);
			String res = "/natives/" + platform + "/" + name;
			Path dir = gameDir.resolve("gbalink").resolve("natives").resolve(platform);
			Path target = dir.resolve(name);
			Files.createDirectories(dir);
			try (InputStream in = GbaNative.class.getResourceAsStream(res)) {
				if (in == null) {
					if (!Files.isRegularFile(target)) {
						loadError = "The GBA emulator library is not included for " + platform + ".";
						return null;
					}
					// fall through: use a manually placed library
				} else {
					byte[] bytes = in.readAllBytes();
					if (!sameContent(target, bytes)) {
						Path tmp = dir.resolve(name + ".tmp");
						Files.write(tmp, bytes);
						try {
							Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							Files.deleteIfExists(tmp);
							if (!Files.isRegularFile(target)) throw e;
							// existing copy is locked (already loaded in this JVM); keep using it
						}
					}
				}
			}
			SymbolLookup lib = SymbolLookup.libraryLookup(target, Arena.global());
			int v = (int) h(Linker.nativeLinker(), lib, "gba_api_version", FunctionDescriptor.of(JAVA_INT)).invokeExact();
			if (v < MIN_API_VERSION || v > API_VERSION) {
				loadError = "GBA emulator library has API version " + v + ", expected " + MIN_API_VERSION + " to " + API_VERSION + ".";
				return null;
			}
			GbaNative n = new GbaNative(lib, v);
			instance = n;
			return n;
		} catch (Throwable t) {
			loadError = "Could not load the GBA emulator library: " + t;
			return null;
		}
	}

	public static synchronized String loadError() {
		return loadError;
	}

	private static boolean sameContent(Path target, byte[] bytes) {
		try {
			if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) return false;
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return Arrays.equals(md.digest(Files.readAllBytes(target)), md.digest(bytes));
		} catch (Exception e) {
			return false;
		}
	}

	static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
}
