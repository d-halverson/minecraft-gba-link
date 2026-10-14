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
 * usage: NetplayHashCheck &lt;gameDir with gbalink/natives/&lt;platform&gt;/lib&gt; &lt;rom.gba&gt; [outFile]
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
			cs[i].linkConfigure(i, players);
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

	public static void main(String[] a) throws Exception {
		Path gameDir = Path.of(a[0]);
		GbaNative n = GbaNative.get(gameDir);
		if (n == null) throw new IllegalStateException("native library not loaded: " + GbaNative.loadError());
		if (!n.supportsNetplay()) throw new IllegalStateException("native library without the virtual RTC");
		Path rom = Path.of(a[1]);
		StringBuilder sb = new StringBuilder();
		sb.append("native api ").append(n.supportsNetplay() ? "2" : "1").append('\n');
		sb.append(run(n, rom, 2));
		sb.append(run(n, rom, 3));
		if (a.length > 2) Files.writeString(Path.of(a[2]), sb.toString());
		System.out.print(sb);
	}
}
