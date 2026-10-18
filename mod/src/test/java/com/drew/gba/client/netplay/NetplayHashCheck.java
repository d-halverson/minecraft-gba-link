package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Cross-platform determinism check (run by CI on Linux, Windows, macOS arm64 and x86_64, and compared): a fixed ROM with
 * fixed scripted inputs and the virtual RTC, 2 and 3 linked consoles in one {@link NetplaySim}, 3000 frames, once straight
 * through and once with a snapshot reload (fresh cores) at frame 1500. Prints one line per check; every platform must print
 * exactly the same text.
 *
 * With a fourth argument (a directory holding gb_dmg.gb and gb_cgb.gbc, made by native/tests/make_gb_rom.py) it also runs the
 * Game Boy / Game Boy Color determinism check: one core per ROM, scripted keys, virtual RTC, 1500 frames, CRC32 of the complete
 * save state and the framebuffer at frames 100, 1000 and 1500 (the same numbers native/tests/test_determinism_gb.c pins).
 *
 * With a gbRomDir that also holds gblink_auto.gb (the serial exerciser, native/tests/make_gb_link_rom.py) it runs two linked Game Boy
 * consoles in one {@link NetplaySim} (GBSIOLockstep, native API 5) with the same scripted keys as test_determinism_gblink.c: the
 * "gblink" lines must equal that test's pinned values, and every platform must print the same text; a second run reloads fresh cores
 * from a snapshot of both consoles at frame 500 ("gblink-reload").
 *
 * usage: NetplayHashCheck &lt;gameDir with gbalink/natives/&lt;platform&gt;/lib&gt; &lt;rom.gba&gt; [outFile [gbRomDir]]
 */
public final class NetplayHashCheck {
	static final long EPOCH = 1_760_000_000L;
	static final int FRAMES = 3000, RELOAD_AT = 1500;
	static final int[] CHECKS = {100, 1000, 1500, 3000};

	/** Deterministic scripted controller input (changes every 16 frames). */
	static int script(int player, long frame) {
		long block = frame / 16;
		long h = (block + 1) * 0x9E3779B97F4A7C15L ^ (42L * 31 + player) * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (int) (h & 0x3F3L);
	}

	static GbaCore[] fresh(GbaNative n, Path rom, int players, long epoch, byte[][] states, byte[][] saves) {
		GbaCore[] cs = new GbaCore[players];
		for (int i = 0; i < players; i++) {
			cs[i] = new GbaCore(n);
			if (cs[i].loadRom(rom) < 0) throw new IllegalStateException("rom");
			if (!cs[i].setRtc(epoch, GbaNative.RTC_VIRTUAL)) throw new IllegalStateException("rtc");
			if (saves != null && saves[i] != null && saves[i].length > 0) cs[i].loadSave(saves[i]);
			if (states != null && !cs[i].loadState(states[i])) throw new IllegalStateException("state");
		}
		return cs;
	}

	static String line(String tag, int frame, NetplaySim sim, int players) {
		StringBuilder sb = new StringBuilder(tag).append(" f=").append(frame).append(" all=").append(Integer.toHexString(sim.hashAll()));
		byte[] fb = new byte[240 * 160 * 4];
		for (int i = 0; i < players; i++) {
			sim.core(i).copyFramebuffer(fb);
			CRC32 c = new CRC32();
			c.update(fb);
			sb.append(" fb").append(i).append('=').append(Long.toHexString(c.getValue()));
		}
		return sb.toString();
	}

	static String run(GbaNative n, Path rom, int players) {
		StringBuilder out = new StringBuilder();
		GbaCore[] cs = fresh(n, rom, players, EPOCH, null, null);
		NetplaySim sim = new NetplaySim(cs, 0);
		int[] keys = new int[players];
		int next = 0;
		for (int f = 0; f < FRAMES; f++) {
			for (int i = 0; i < players; i++) keys[i] = script(i, f);
			if (!sim.tick(keys)) throw new IllegalStateException("core error");
			if (next < CHECKS.length && f + 1 == CHECKS[next]) out.append(line(players + "p", f + 1, sim, players)).append('\n');
			if (f + 1 == RELOAD_AT) {
				// snapshot every console and continue in fresh cores (what a resync / session start does)
				byte[][] st = new byte[players][], sv = new byte[players][];
				for (int i = 0; i < players; i++) { st[i] = sim.core(i).saveState(); sv[i] = sim.core(i).getSave(); }
				NetplaySim reloaded = new NetplaySim(fresh(n, rom, players, EPOCH + (long) RELOAD_AT * 280896L / 16777216L, st, sv), 0);
				sim.close();
				sim = reloaded;
			}
			if (next < CHECKS.length && f + 1 == CHECKS[next]) next++;
		}
		sim.close();
		return out.toString();
	}

	/** Same pseudo random key script as test_determinism_gb.c (Game Boy keys are the low 8 bits). */
	static int gbScript(long frame) {
		long block = frame / 16;
		long h = (block + 1) * 0x9E3779B97F4A7C15L ^ (42L * 31 + 7) * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (int) (h & 0xFFL);
	}

	static String runGameBoy(GbaNative n, Path rom) {
		StringBuilder out = new StringBuilder();
		try (GbaCore c = new GbaCore(n)) {
			if (c.loadRom(rom) < 0 || !c.isGameBoy()) throw new IllegalStateException("not a Game Boy ROM: " + rom);
			if (!c.setRtc(EPOCH, GbaNative.RTC_VIRTUAL)) throw new IllegalStateException("rtc");
			short[] pcm = new short[4096];
			byte[] fb = new byte[240 * 160 * 4];
			int next = 0;
			for (int f = 0; f < 1500; f++) {
				c.setKeys(gbScript(f));
				if (c.runFrame() != GbaNative.RUN_FRAME_DONE) throw new IllegalStateException("core error");
				while (c.readAudio(pcm, 2048) > 0) { }
				if (next < 3 && f + 1 == new int[] {100, 1000, 1500}[next]) {
					CRC32 crc = new CRC32();
					crc.update(c.saveState());
					c.copyFramebuffer(fb);
					crc.update(fb, 0, c.frameWidth() * c.frameHeight() * 4);
					out.append("gb ").append(rom.getFileName()).append(" f=").append(f + 1).append(" all=")
						.append(String.format("%08x", crc.getValue())).append('\n');
					next++;
				}
			}
		}
		return out.toString();
	}

	/** Two linked Game Boy consoles, 1000 frames; checks at 100, 500 and 1000. {@code reload}: fresh cores from a snapshot at frame 500. */
	static String runGameBoyLink(GbaNative n, Path rom, boolean reload) {
		StringBuilder out = new StringBuilder();
		String tag = reload ? "gblink-reload" : "gblink";
		NetplaySim sim = new NetplaySim(fresh(n, rom, 2, EPOCH, null, null), 0);
		int[] keys = new int[2];
		byte[] fb = new byte[160 * 144 * 4];
		int[] checks = {100, 500, 1000};
		for (int f = 0; f < 1000; f++) {
			for (int i = 0; i < 2; i++) keys[i] = script(i, f);
			if (!sim.tick(keys)) throw new IllegalStateException("core error");
			if (reload && f + 1 == 500) {
				byte[][] st = new byte[2][], sv = new byte[2][];
				for (int i = 0; i < 2; i++) { st[i] = sim.core(i).saveState(); sv[i] = sim.core(i).getSave(); }
				NetplaySim reloaded = new NetplaySim(fresh(n, rom, 2, EPOCH + (long) 500 * 280896L / 16777216L, st, sv), 0);
				sim.close();
				sim = reloaded;
			}
			for (int c : checks) {
				if (f + 1 != c) continue;
				StringBuilder sb = new StringBuilder(tag).append(" f=").append(c).append(" states=").append(String.format("%08x", sim.hashAll() & 0xFFFFFFFFL));
				CRC32 all = new CRC32();
				for (int i = 0; i < 2; i++) { sim.core(i).copyFramebuffer(fb); all.update(fb, 0, 160 * 144 * 4); }
				sb.append(" frames=").append(String.format("%08x", all.getValue()));
				out.append(sb).append('\n');
			}
		}
		sim.close();
		return out.toString();
	}

	public static void main(String[] a) throws Exception {
		Path gameDir = Path.of(a[0]);
		GbaNative n = GbaNative.get(gameDir);
		if (n == null) throw new IllegalStateException("native library not loaded: " + GbaNative.loadError());
		if (!n.supportsNetplay()) throw new IllegalStateException("native library without lockstep groups (API 3)");
		Path rom = Path.of(a[1]);
		StringBuilder sb = new StringBuilder();
		sb.append("native api ").append(n.supportsNetplay() ? "3" : "1").append('\n');
		sb.append(run(n, rom, 2));
		sb.append(run(n, rom, 3));
		if (a.length > 3) {
			if (!n.supportsGameBoy()) throw new IllegalStateException("native library without Game Boy support (API 4)");
			sb.append("gb api ").append(n.apiVersion()).append('\n');
			sb.append(runGameBoy(n, Path.of(a[3], "gb_dmg.gb")));
			sb.append(runGameBoy(n, Path.of(a[3], "gb_cgb.gbc")));
			Path link = Path.of(a[3], "gblink_auto.gb");
			if (Files.isRegularFile(link)) {
				if (!n.supportsGameBoyLink()) throw new IllegalStateException("native library without Game Boy link support (API 5)");
				sb.append(runGameBoyLink(n, link, false));
				sb.append(runGameBoyLink(n, link, true));
			}
		}
		if (a.length > 2) Files.writeString(Path.of(a[2]), sb.toString());
		System.out.print(sb);
	}
}
