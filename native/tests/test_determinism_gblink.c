/* Determinism check for two linked Game Boy consoles (lockstep group, API 5): gblink_auto.gb on both consoles, scripted keys per
 * player (A presses make a console the link master, see make_gb_link_rom.py), virtual RTC. Prints a CRC32 over the complete save
 * states of both consoles and their framebuffers at fixed frames; the values must be identical on every platform and architecture
 * (CI compares the Java NetplayHashCheck output, which runs exactly this).   test_determinism_gblink <gb-link-rom-dir> */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t crc_table[256];
static void crc_init(void) {
	for (uint32_t i = 0; i < 256; ++i) {
		uint32_t c = i;
		for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		crc_table[i] = c;
	}
}
static uint32_t crc_update(uint32_t crc, const uint8_t* p, size_t n) {
	crc = ~crc;
	while (n--) crc = crc_table[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
	return ~crc;
}

/* the same key script as NetplayHashCheck.script (per player, changes every 16 frames) */
static int script(int player, long long frame) {
	long long block = frame / 16;
	uint64_t h = ((uint64_t) (block + 1) * 0x9E3779B97F4A7C15ull) ^ ((uint64_t) (42 * 31 + player) * 0xC2B2AE3D27D4EB4Full);
	h ^= h >> 29;
	h *= 0xBF58476D1CE4E5B9ull;
	h ^= h >> 32;
	return (int) (h & 0x3F3ull);
}

static const int g_frames[3] = { 100, 500, 1000 };
static const uint32_t g_expect[3] = { 0x386cb6e6u, 0x5e0501e7u, 0xdea8bdc7u };

int main(int argc, char** argv) {
	if (argc < 2) return 2;
	crc_init();
	char path[1024];
	snprintf(path, sizeof(path), "%s/gblink_auto.gb", argv[1]);
	gba_core* c[2];
	for (int i = 0; i < 2; ++i) {
		c[i] = gba_create();
		if (!c[i] || gba_load_rom(c[i], path) != 0) { printf("cannot load %s\n", path); return 2; }
		gba_set_rtc(c[i], 1760000000, GBA_RTC_VIRTUAL);
	}
	gba_group* g = gba_group_create(2);
	for (int i = 0; i < 2; ++i) if (gba_group_add(g, c[i]) != 0) { printf("group add failed\n"); return 1; }
	int next = 0, fail = 0;
	for (long long f = 0; f < 1000; ++f) {
		for (int i = 0; i < 2; ++i) gba_set_keys(c[i], (uint32_t) script(i, f));
		if (gba_group_run_frame(g) != GBA_RUN_FRAME_DONE) { printf("run error\n"); return 1; }
		for (int i = 0; i < 2; ++i) { int16_t pcm[4096]; while (gba_read_audio(c[i], pcm, 2048) > 0) { } }
		if (next < 3 && f + 1 == g_frames[next]) {
			uint32_t h = 0;
			for (int i = 0; i < 2; ++i) {
				size_t n = gba_state_size(c[i]);
				uint8_t* st = malloc(n);
				if (gba_save_state(c[i], st, n) != n) { printf("save state failed\n"); return 1; }
				h = crc_update(h, st, n);
				free(st);
			}
			uint32_t fb = 0;
			for (int i = 0; i < 2; ++i) fb = crc_update(fb, gba_framebuffer(c[i]), (size_t) gba_frame_width(c[i]) * gba_frame_height(c[i]) * 4);
			printf("gblink f=%d states=%08x frames=%08x %s\n", g_frames[next], h, fb, h == g_expect[next] ? "ok" : "MISMATCH");
			if (h != g_expect[next]) fail = 1;
			++next;
		}
	}
	int t0 = gba_priv_read16(c[0], 0xC111) & 0xFF, t1 = gba_priv_read16(c[1], 0xC111) & 0xFF;
	printf("transfers logged: %d and %d\n", t0, t1);
	if (t0 < 20 || t1 < 20) { printf("too few link transfers: the scripted keys never started a link\n"); fail = 1; }
	gba_group_destroy(g);
	for (int i = 0; i < 2; ++i) gba_destroy(c[i]);
	return fail;
}
