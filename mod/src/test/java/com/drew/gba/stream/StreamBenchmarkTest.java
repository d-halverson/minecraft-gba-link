package com.drew.gba.stream;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bandwidth / speed numbers for the stream codec (printed to stdout; run with `gradlew test -i`).
 * Also verifies exactness and the packet-size cap on every scene.
 */
class StreamBenchmarkTest {
	@TempDir static Path tmp;
	static final int N = 240 * 160 * 4;

	record Result(String name, int frames, double kbps, int maxPacket, double encMs, double decMs, int keys) {}

	static final List<Result> results = new ArrayList<>();

	static Result bench(String name, List<byte[]> frames, boolean exact) {
		// warm-up (JIT) on a copy of the sequence, not measured
		for (int rep = 0; rep < 2; rep++) {
			VideoEncoder e = new VideoEncoder();
			VideoDecoder d = new VideoDecoder();
			byte[] o = new byte[N];
			for (byte[] f : frames) d.decode(e.encode(f, false), o);
		}
		VideoEncoder enc = new VideoEncoder();
		VideoDecoder dec = new VideoDecoder();
		byte[] out = new byte[N];
		long bytes = 0, encNs = 0, decNs = 0;
		int max = 0, keys = 0;
		boolean lossless = true;
		String why = "";
		int idx = 0;
		for (byte[] f : frames) {
			long t0 = System.nanoTime();
			byte[] p = enc.encode(f, false);
			long t1 = System.nanoTime();
			boolean ok = dec.decode(p, out);
			long t2 = System.nanoTime();
			assertTrue(ok);
			encNs += t1 - t0;
			decNs += t2 - t1;
			bytes += p.length;
			max = Math.max(max, p.length);
			if ((p[1] & 1) != 0) keys++;
			if (!Arrays.equals(f, out) && lossless) {
				lossless = false;
				why = " first mismatch frame " + idx + " pkt " + p.length + " flags " + p[1];
			}
			idx++;
		}
		if (exact) assertTrue(lossless, name + " must be lossless" + why);
		Result r = new Result(name + (lossless ? "" : " (coarse)"), frames.size(), bytes / (double) frames.size() * 30 / 1024.0, max,
			encNs / 1e6 / frames.size(), decNs / 1e6 / frames.size(), keys);
		results.add(r);
		return r;
	}

	static void print() {
		System.out.printf("%n%-34s %6s %9s %9s %8s %8s %5s%n", "scene", "frames", "KB/s@30", "max pkt", "enc ms", "dec ms", "keys");
		for (Result r : results) System.out.printf("%-34s %6d %9.1f %9d %8.3f %8.3f %5d%n", r.name, r.frames, r.kbps, r.maxPacket, r.encMs, r.decMs, r.keys);
	}

	@Test
	void syntheticScenes() {
		SyntheticScenes.World w = new SyntheticScenes.World(100);
		int n = 180;
		record Scene(String name, java.util.function.IntFunction<byte[]> gen, boolean exact) {}
		Random nr = new Random(1);
		List<Scene> scenes = List.of(
			new Scene("static", i -> SyntheticScenes.scroll(w, 0, 0, 0, 0), true),
			new Scene("sprites only (3, static bg)", i -> SyntheticScenes.scroll(w, i, 0, 0, 3), true),
			new Scene("scroll x 1px/frame + 3 sprites", i -> SyntheticScenes.scroll(w, i, 1, 0, 3), true),
			new Scene("scroll x 2px/frame", i -> SyntheticScenes.scroll(w, i, 2, 0, 0), true),
			new Scene("scroll y 1px/frame + sprites", i -> SyntheticScenes.scroll(w, i, 0, 1, 2), true),
			new Scene("scroll diag 2,2 px/frame + sprites", i -> SyntheticScenes.scroll(w, i, 2, 2, 3), true),
			new Scene("scroll 4px/frame (fast)", i -> SyntheticScenes.scroll(w, i, 4, 0, 1), true),
			new Scene("text box typing", i -> SyntheticScenes.textBox(w, i), true),
			new Scene("fade to white (30 fr) + hold", i -> SyntheticScenes.fade(w, i, 30), true),
			new Scene("full-screen flash (4-frame)", i -> SyntheticScenes.flash(w, i), true),
			new Scene("random noise (worst case)", i -> SyntheticScenes.noise(nr), false));
		for (Scene s : scenes) {
			List<byte[]> fr = new ArrayList<>();
			for (int i = 0; i < n; i++) fr.add(s.gen.apply(i));
			Result r = bench(s.name, fr, s.exact);
			assertTrue(r.maxPacket <= VideoEncoder.MAX_DELTA_PACKET || r.keys > 0 && r.name.contains("noise"), s.name + " max " + r.maxPacket);
			assertTrue(r.encMs < 2.0, s.name + " encode " + r.encMs);
		}
		print();
	}

	@Test
	void emulatorRoms() throws IOException {
		String platform = GbaNative.platform();
		String lib = platform.startsWith("macos") ? "libgbacore.dylib" : platform.startsWith("windows") ? "gbacore.dll" : "libgbacore.so";
		Path src = Path.of("..", "dist", "natives", platform, lib).toAbsolutePath().normalize();
		Assumptions.assumeTrue(Files.isRegularFile(src), "native library not built for " + platform);
		Path dir = tmp.resolve("gbalink").resolve("natives").resolve(platform);
		Files.createDirectories(dir);
		Files.copy(src, dir.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
		GbaNative natives = GbaNative.get(tmp);
		Assumptions.assumeTrue(natives != null, "native library failed to load: " + GbaNative.loadError());
		List<Path> roms = new ArrayList<>();
		for (String d : new String[] {"src/test/resources/roms", "../native/third_party/gba-tests"}) {
			try (var s = Files.walk(Path.of(d))) {
				s.filter(p -> p.toString().endsWith(".gba")).sorted().forEach(roms::add);
			}
		}
		Assumptions.assumeFalse(roms.isEmpty());
		List<Result> mine = new ArrayList<>();
		for (Path rom : roms) {
			List<byte[]> frames = new ArrayList<>();
			AudioEncoder aenc = new AudioEncoder();
			AudioDecoder adec = new AudioDecoder();
			long audioBytes = 0;
			int audioSamples = 0;
			short[] ab = new short[4096 * 2], mono = new short[8192];
			try (GbaCore core = new GbaCore(natives)) {
				if (core.loadRom(rom) != 0) continue;
				for (int f = 0; f < 600; f++) {
					core.setKeys(f % 40 < 3 ? (f / 40) % 2 == 0 ? 0x10 : 0x20 : f % 90 == 5 ? 1 : 0);
					core.runFrame();
					int fr = core.readAudio(ab, 4096);
					if (f % 2 == 1) {
						byte[] px = new byte[N];
						core.copyFramebuffer(px);
						frames.add(px);
					}
					byte[] ap = aenc.encode(ab, fr); // per-frame (60 Hz) chunks here; real use is per 30 Hz
					audioBytes += ap.length;
					audioSamples += adec.decode(ap, mono);
				}
			}
			String name = "emu " + rom.getFileName();
			Result r = bench(name, frames, true);
			mine.add(r);
			System.out.printf("   %s: audio %.2f KB/s (60 Hz chunks incl. 6 B headers), %d samples decoded%n", name, audioBytes / 10.0 / 1024, audioSamples);
		}
		print();
	}

	@Test
	void audioSpeed() {
		AudioEncoder enc = new AudioEncoder();
		AudioDecoder dec = new AudioDecoder();
		short[] in = AudioCodecTest.sine(1092, 440, 9000, 0), out = new short[2048];
		for (int i = 0; i < 2000; i++) dec.decode(enc.encode(in, 1092), out);
		long t0 = System.nanoTime();
		int n = 3000;
		byte[] p = null;
		for (int i = 0; i < n; i++) p = enc.encode(in, 1092);
		long t1 = System.nanoTime();
		for (int i = 0; i < n; i++) dec.decode(p, out);
		long t2 = System.nanoTime();
		System.out.printf("audio: encode %.3f ms/chunk (1092 frames), decode %.3f ms/chunk, packet %d B = %.2f KB/s at 30 Hz%n",
			(t1 - t0) / 1e6 / n, (t2 - t1) / 1e6 / n, p.length, p.length * 30 / 1024.0);
	}
}
