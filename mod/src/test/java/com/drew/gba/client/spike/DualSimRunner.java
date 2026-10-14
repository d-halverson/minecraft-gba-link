package com.drew.gba.client.spike;

import com.drew.gba.client.emu.GbaNative;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the dual simulation in its own JVM and prints frame hashes (determinism across processes). */
public final class DualSimRunner {
	public static final int[] CHECK_FRAMES = {100, 1000, 5000};

	public static String hashes(DualSim d, byte[] fb, int frame) {
		byte[] s0 = d.c0.saveState(), s1 = d.c1.saveState();
		return "f=" + frame + " fb0=" + Long.toHexString(SpikeSupport.fbHash(d.c0, fb)) + " fb1=" + Long.toHexString(SpikeSupport.fbHash(d.c1, fb))
			+ " mem0=" + Long.toHexString(SpikeSupport.memHash(s0)) + " mem1=" + Long.toHexString(SpikeSupport.memHash(s1))
			+ " ewram0=" + Long.toHexString(SpikeSupport.crc(s0, SpikeSupport.EWRAM, SpikeSupport.EWRAM_SIZE))
			+ " ewram1=" + Long.toHexString(SpikeSupport.crc(s1, SpikeSupport.EWRAM, SpikeSupport.EWRAM_SIZE))
			+ " full0=" + Long.toHexString(SpikeSupport.crc(s0, 0, s0.length)) + " full1=" + Long.toHexString(SpikeSupport.crc(s1, 0, s1.length));
	}

	public static String run(GbaNative n, Path rom, int delay, long seed, boolean childFirst, boolean drain) {
		StringBuilder sb = new StringBuilder();
		byte[] fb = new byte[240 * 160 * 4];
		try (DualSim d = new DualSim(n, rom, delay, seed, childFirst)) {
			d.drainAudio = drain;
			int next = 0;
			for (int f = 1; f <= CHECK_FRAMES[CHECK_FRAMES.length - 1]; f++) {
				if (!d.tick()) throw new IllegalStateException("core error");
				if (f == CHECK_FRAMES[next]) { sb.append(hashes(d, fb, f)).append('\n'); next++; }
			}
		}
		return sb.toString();
	}

	public static void main(String[] a) throws Exception {
		Path gameDir = Path.of(a[0]);
		GbaNative n = SpikeSupport.loadNative(gameDir);
		Path rom = SpikeSupport.extractRom(gameDir.resolve("roms"), "link_pokesim.gba");
		String out = run(n, rom, 3, 42, false, true);
		if (a.length > 1) Files.writeString(Path.of(a[1]), out); else System.out.print(out);
	}
}
