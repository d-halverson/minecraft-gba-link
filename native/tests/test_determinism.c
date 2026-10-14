/* Determinism check: two cores linked with mGBA's lockstep inside one gba_group, as input-sync netplay does
 * (NetplaySim in the mod), fixed ROM, scripted inputs, virtual RTC. Prints a CRC32 over both complete
 * save states at fixed frames. The expected values were produced by the Java NetplaySim on macOS x86_64 and must be
 * identical on every platform and architecture (CI also runs the Java version on Linux, Windows and macOS arm64). */
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

static int script(int player, long long frame) {
	long long block = frame / 16;
	uint64_t h = ((uint64_t) (block + 1) * 0x9E3779B97F4A7C15ull) ^ ((uint64_t) (42 * 31 + player) * 0xC2B2AE3D27D4EB4Full);
	h ^= h >> 29;
	h *= 0xBF58476D1CE4E5B9ull;
	h ^= h >> 32;
	return (int) (h & 0x3F3ull);
}

#define N 2
static gba_core* c[N];
static gba_group* grp;

static void tick(long long f) {
	for (int i = 0; i < N; ++i) gba_set_keys(c[i], script(i, f));
	if (gba_group_run_frame(grp) != GBA_RUN_FRAME_DONE) { printf("group error\n"); exit(1); }
	int16_t pcm[4096];
	for (int i = 0; i < N; ++i) while (gba_read_audio(c[i], pcm, 2048) > 0) { }
}

static uint32_t hash_all(void) {
	uint32_t crc = 0;
	for (int i = 0; i < N; ++i) {
		size_t n = gba_state_size(c[i]);
		uint8_t* st = malloc(n);
		if (gba_save_state(c[i], st, n) != n) { printf("save state failed\n"); exit(1); }
		crc = crc_update(crc, st, n);
		free(st);
	}
	return crc;
}

int main(int argc, char** argv) {
	if (argc < 2) return 2;
	crc_init();
	char path[1024];
	snprintf(path, sizeof(path), "%s/link_pokesim.gba", argv[1]);
	for (int i = 0; i < N; ++i) {
		c[i] = gba_create();
		if (!c[i] || gba_load_rom(c[i], path) != 0) { printf("cannot load %s\n", path); return 2; }
		gba_set_rtc(c[i], 1760000000, GBA_RTC_VIRTUAL);
	}
	grp = gba_group_create(N);
	for (int i = 0; i < N; ++i) if (gba_group_add(grp, c[i]) != 0) { printf("group add failed\n"); return 2; }
	static const struct { int frame; uint32_t expect; } checks[] = {
		{ 100, 0x7767ecd7u }, { 1000, 0x22acf324u }, { 1500, 0x59acab12u } };
	int next = 0, fail = 0;
	for (long long f = 0; f < 1500; ++f) {
		tick(f);
		if (next < 3 && f + 1 == checks[next].frame) {
			uint32_t h = hash_all();
			printf("2p f=%d all=%08x %s\n", checks[next].frame, h, h == checks[next].expect ? "ok" : "MISMATCH");
			if (h != checks[next].expect) fail = 1;
			++next;
		}
	}
	gba_group_destroy(grp);
	for (int i = 0; i < N; ++i) gba_destroy(c[i]);
	return fail;
}
