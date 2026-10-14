/* Headless tests: load a jsmolka gba-tests ROM, run frames, check video/audio/save-state/save-data. */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void write_ppm(const char* path, const uint8_t* rgba) {
	FILE* f = fopen(path, "wb");
	if (!f) { return; }
	fprintf(f, "P6\n%d %d\n255\n", GBA_WIDTH, GBA_HEIGHT);
	for (int i = 0; i < GBA_WIDTH * GBA_HEIGHT; ++i) {
		fwrite(rgba + i * 4, 1, 3, f);
	}
	fclose(f);
}

static uint32_t px(const uint8_t* fb, int i) {
	return fb[i * 4] | (fb[i * 4 + 1] << 8) | (fb[i * 4 + 2] << 16) | ((uint32_t) fb[i * 4 + 3] << 24);
}

/* returns number of distinct colours; fills dominant colour fraction */
static int analyse(const uint8_t* fb, uint32_t* dominant, double* frac) {
	uint32_t colors[64];
	int counts[64];
	int n = 0;
	for (int i = 0; i < GBA_WIDTH * GBA_HEIGHT; ++i) {
		uint32_t c = px(fb, i);
		int k;
		for (k = 0; k < n; ++k) { if (colors[k] == c) { break; } }
		if (k == n) {
			if (n == 64) { continue; }
			colors[n] = c; counts[n] = 0; ++n;
		}
		++counts[k];
	}
	int best = 0;
	for (int k = 1; k < n; ++k) { if (counts[k] > counts[best]) { best = k; } }
	*dominant = colors[best];
	*frac = counts[best] / (double) (GBA_WIDTH * GBA_HEIGHT);
	return n;
}

static uint64_t fnv(const uint8_t* p, size_t n) {
	uint64_t h = 1469598103934665603ull;
	for (size_t i = 0; i < n; ++i) { h = (h ^ p[i]) * 1099511628211ull; }
	return h;
}

static int run_frames(gba_core* c, int n) {
	for (int i = 0; i < n; ++i) {
		if (gba_run_frame(c) != GBA_RUN_FRAME_DONE) { return -1; }
	}
	return 0;
}

int main(int argc, char** argv) {
	if (argc < 3) { fprintf(stderr, "usage: %s rom.gba out.ppm [expected_frame_hash_hex|-] [save]\n", argv[0]); return 2; }
	const char* rom = argv[1];
	const char* out = argv[2];
	const char* want_hash = argc > 3 && strcmp(argv[3], "-") != 0 ? argv[3] : NULL;
	int do_save = argc > 4 && strcmp(argv[4], "save") == 0;
	const char* want_color = argc > 5 ? argv[5] : NULL; /* RRGGBB hex of one of the two main colours */

	CHECK(gba_api_version() == 3, "api version");
	gba_core* c = gba_create();
	CHECK(c != NULL, "create");
	CHECK(gba_load_rom(c, rom) == 0, "load_rom %s", rom);
	CHECK(gba_load_rom(c, "/nonexistent/x.gba") < 0, "load missing rom fails");
	CHECK(gba_load_rom(c, rom) == 0, "reload rom");

	char title[13], code[5];
	CHECK(gba_rom_info(c, title, code) == 0, "rom_info");
	printf("  rom info: title='%s' code='%s'\n", title, code);

	/* video + audio over 300 frames */
	size_t total_audio = 0, steady_audio = 0, minf = 99999, maxf = 0;
	static int16_t abuf[4096 * 2];
	for (int f = 0; f < 300; ++f) {
		int r = gba_run_frame(c);
		CHECK(r == GBA_RUN_FRAME_DONE, "run_frame returned %d", r);
		size_t got = gba_read_audio(c, abuf, 4096);
		total_audio += got;
		if (f >= 10) {
			steady_audio += got;
			if (got < minf) { minf = got; }
			if (got > maxf) { maxf = got; }
		}
	}
	/* mGBA hands audio over in 4-sample blocks, so single frames jitter 548/552 around 548.59 */
	double avg = steady_audio / 290.0;
	printf("  audio: %zu frames over 300 video frames; steady-state avg %.2f/frame (min %zu, max %zu)\n", total_audio, avg, minf, maxf);
	CHECK(minf >= 544 && maxf <= 553, "per-frame audio out of range");
	CHECK(avg > 548.2 && avg < 549.0, "avg audio frames per video frame %.3f", avg);

	const uint8_t* fb = gba_framebuffer(c);
	write_ppm(out, fb);
	uint32_t dom; double frac;
	int ncol = analyse(fb, &dom, &frac);
	printf("  video: %d distinct colours (max 64 tracked), dominant ABGR=%08X (%.1f%%)\n", ncol, dom, frac * 100.0);
	CHECK(ncol >= 2, "framebuffer is blank");
	for (int i = 0; i < GBA_WIDTH * GBA_HEIGHT; ++i) {
		if (fb[i * 4 + 3] != 255) { CHECK(0, "alpha != 255 at pixel %d", i); break; }
	}
	if (want_color) {
		/* verify the memory byte order R,G,B,A of the output with a ROM whose palette is known */
		unsigned rgb = (unsigned) strtoul(want_color, NULL, 16);
		int found = 0;
		for (int i = 0; i < GBA_WIDTH * GBA_HEIGHT && !found; ++i) {
			found = fb[i * 4] == ((rgb >> 16) & 0xFF) && fb[i * 4 + 1] == ((rgb >> 8) & 0xFF)
			        && fb[i * 4 + 2] == (rgb & 0xFF) && fb[i * 4 + 3] == 0xFF;
		}
		CHECK(found, "no pixel with bytes R,G,B,A = %s,FF", want_color);
	}
	char hbuf[32];
	snprintf(hbuf, sizeof(hbuf), "%016llx", (unsigned long long) fnv(fb, GBA_WIDTH * GBA_HEIGHT * 4));
	printf("  frame hash = %s\n", hbuf);
	if (want_hash) {
		CHECK(strcmp(hbuf, want_hash) == 0, "frame hash %s != expected %s", hbuf, want_hash);
	}

	/* state round trip: save, run N, capture, load, run N, compare */
	size_t ss = gba_state_size(c);
	CHECK(ss > 100000, "state size %zu", ss);
	uint8_t* st = malloc(ss);
	CHECK(gba_save_state(c, st, ss) == ss, "save_state");
	CHECK(gba_save_state(c, st, ss - 1) == 0, "save_state with small cap fails");
	CHECK(run_frames(c, 40) == 0, "run 40");
	uint8_t a[GBA_WIDTH * GBA_HEIGHT * 4];
	memcpy(a, gba_framebuffer(c), sizeof(a));
	gba_read_audio(c, abuf, 4096);
	CHECK(gba_load_state(c, st, ss) == 0, "load_state");
	CHECK(gba_load_state(c, st, ss - 5) != 0, "load_state with bad length fails");
	CHECK(run_frames(c, 40) == 0, "rerun 40");
	CHECK(memcmp(a, gba_framebuffer(c), sizeof(a)) == 0, "framebuffer differs after state round-trip");
	free(st);

	if (do_save) {
		/* battery save API: the SRAM test ROM writes to SRAM, so it must be detected and sized */
		size_t sz = gba_save_size(c);
		printf("  save size = %zu, dirty = %d\n", sz, gba_save_dirty(c));
		CHECK(sz == 32768, "SRAM save size %zu", sz);
		CHECK(gba_save_dirty(c) == 1, "save should be dirty before first get");
		uint8_t* sv = malloc(sz);
		CHECK(gba_get_save(c, sv, sz) == sz, "get_save");
		CHECK(gba_get_save(c, sv, sz - 1) == 0, "get_save with small buffer fails");
		CHECK(gba_save_dirty(c) == 0, "save should be clean after get");
		CHECK(sv[0] != 0xFF || sv[32] != 0xFF, "test ROM data not present in save");
		/* a fresh core given this save must see it before running any code */
		gba_core* c3 = gba_create();
		CHECK(gba_load_rom(c3, rom) == 0, "c3 load");
		sv[0] = 0x12; /* the ROM's first test expects blank (0xFF) SRAM at offset 0 */
		CHECK(gba_load_save(c3, sv, sz) == 0, "load_save");
		CHECK(gba_save_size(c3) == sz, "c3 save size %zu", gba_save_size(c3));
		uint8_t* sv2 = malloc(sz);
		CHECK(gba_get_save(c3, sv2, sz) == sz, "c3 get_save");
		CHECK(memcmp(sv, sv2, sz) == 0, "loaded save differs from original");
		CHECK(gba_save_dirty(c3) == 0, "c3 should be clean");
		/* the ROM's first test expects blank (0xFF) SRAM, so with our save loaded it must fail test 001 */
		CHECK(run_frames(c3, 30) == 0, "c3 run");
		CHECK(fnv(gba_framebuffer(c3), GBA_WIDTH * GBA_HEIGHT * 4) != fnv(fb, GBA_WIDTH * GBA_HEIGHT * 4),
		      "ROM behaved as if the loaded save was blank");
		gba_destroy(c3);
		free(sv); free(sv2);
		/* reset keeps the save */
		gba_reset(c);
		CHECK(gba_save_size(c) == 32768, "save size after reset");
	}

	/* a second, independent core must not interfere */
	gba_core* c2 = gba_create();
	CHECK(gba_load_rom(c2, rom) == 0, "second core load");
	CHECK(run_frames(c2, 10) == 0, "second core run");
	gba_destroy(c2);
	CHECK(run_frames(c, 5) == 0, "first core still runs");

	/* keys (no crash) */
	gba_set_keys(c, 0x3FF);
	CHECK(run_frames(c, 2) == 0, "run with keys");
	gba_set_keys(c, 0);

	gba_reset(c);
	CHECK(run_frames(c, 10) == 0, "run after reset");

	gba_destroy(c);
	printf("%s\n", g_fail ? "RESULT: FAIL" : "RESULT: PASS");
	return g_fail ? 1 : 0;
}
