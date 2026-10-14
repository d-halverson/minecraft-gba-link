/* Determinism check: two cores linked through the zero-latency, fixed-order router used by input-sync netplay
 * (NetplaySim in the mod; same algorithm), fixed ROM, scripted inputs, virtual RTC. Prints a CRC32 over both complete
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
#define MAXQ 4096
struct Msg { int from; uint8_t d[GBA_LINK_MSG_MAX]; size_t n; };
static struct Msg inbox[N][MAXQ];
static int qn[N];
static gba_core* c[N];

static void pump(int i) {
	uint8_t m[GBA_LINK_MSG_MAX];
	size_t n;
	while ((n = gba_link_poll_outgoing(c[i], m, sizeof(m))) > 0) {
		for (int j = 0; j < N; ++j) {
			if (j == i) continue;
			struct Msg* q = &inbox[j][qn[j]++];
			q->from = i;
			q->n = n;
			memcpy(q->d, m, n);
		}
	}
}
static void deliver(int i) {
	for (int k = 0; k < qn[i]; ++k) gba_link_deliver(c[i], inbox[i][k].from, inbox[i][k].d, inbox[i][k].n);
	qn[i] = 0;
	pump(i);
}
static void run_child(void) {
	deliver(1);
	if (gba_run_frame(c[1]) != GBA_RUN_FRAME_DONE) { printf("child blocked\n"); exit(1); }
	pump(1);
	deliver(0);
}

static void tick(long long f) {
	gba_set_keys(c[0], script(0, f));
	gba_set_keys(c[1], script(1, f));
	int ran_child = 0, stalls = 0;
	for (;;) {
		int r = gba_run_frame(c[0]);
		pump(0);
		if (r == GBA_RUN_ERROR) { printf("core error\n"); exit(1); }
		if (r == GBA_RUN_FRAME_DONE) break;
		int fresh = qn[1] > 0;
		deliver(1);
		deliver(0);
		if (!fresh) {
			if (++stalls > 64) { printf("stuck\n"); exit(1); }
			run_child();
			ran_child = 1;
		}
	}
	if (!ran_child) run_child();
	int16_t pcm[4096];
	while (gba_read_audio(c[0], pcm, 2048) > 0) { }
	while (gba_read_audio(c[1], pcm, 2048) > 0) { }
	for (int i = 0; i < 8 && (qn[0] || qn[1]); ++i) { deliver(0); deliver(1); }
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
		gba_link_configure(c[i], i, N);
	}
	pump(0);
	pump(1);
	static const struct { int frame; uint32_t expect; } checks[] = {
		{ 100, 0xfd818a81u }, { 1000, 0x8d648ff2u }, { 1500, 0x4fc1da04u } };
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
	for (int i = 0; i < N; ++i) gba_destroy(c[i]);
	return fail;
}
