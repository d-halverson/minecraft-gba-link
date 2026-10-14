/* Lockstep group tests (API 3): cores linked with mGBA's own GBASIOLockstep and stepped by gba_group_run_frame.
 * The ROMs are the ones of test_link (tests/make_link_rom.py) plus "gen3", which mimics Pokemon Gen 3's link code:
 * role from the SIOCNT SD/SI terminals, transfers started from a Timer 3 IRQ only while SD = 1, ID / error bits read
 * in the SIO IRQ. Note mGBA's lockstep gives a Normal-mode master 0xFFFFFFFF (it does not model the daisy chain's
 * return path), so the Normal tests check what the followers receive. */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const char* g_romdir;
#define EW 0x02000000u
#define LOGBASE (EW + 0x100)

struct Grp {
	gba_core* c[4];
	gba_group* g;
	int n;
	int frames;
};

static char* rompath(const char* name) {
	static char buf[4][1024];
	static int i;
	i = (i + 1) % 4;
	snprintf(buf[i], sizeof(buf[i]), "%s/link_%s.gba", g_romdir, name);
	return buf[i];
}

static void grp_init(struct Grp* G, int n, const char** roms, const uint32_t* bases) {
	memset(G, 0, sizeof(*G));
	G->n = n;
	for (int i = 0; i < n; ++i) {
		G->c[i] = gba_create();
		if (!G->c[i] || gba_load_rom(G->c[i], rompath(roms[i])) != 0) { printf("  cannot load %s\n", rompath(roms[i])); exit(2); }
		gba_priv_write32(G->c[i], EW + 0xF0, bases[i]);
		gba_priv_write32(G->c[i], EW + 0xF4, 0);
	}
	G->g = gba_group_create(n);
	for (int i = 0; i < n; ++i) CHECK(gba_group_add(G->g, G->c[i]) == 0, "group add");
}

static void grp_free(struct Grp* G) {
	gba_group_destroy(G->g);
	for (int i = 0; i < G->n; ++i) gba_destroy(G->c[i]);
}

static void step(struct Grp* G) {
	int16_t pcm[4096];
	CHECK(gba_group_run_frame(G->g) == GBA_RUN_FRAME_DONE, "group frame");
	for (int i = 0; i < G->n; ++i) while (gba_read_audio(G->c[i], pcm, 2048) > 0) { }
	++G->frames;
}

static uint32_t count_of(struct Grp* G, int i) { return gba_priv_read32(G->c[i], EW); }
static uint32_t logw(struct Grp* G, int i, uint32_t k, uint32_t off) { return gba_priv_read32(G->c[i], LOGBASE + 16 * k + off); }
static uint16_t logh(struct Grp* G, int i, uint32_t k, uint32_t off) { return gba_priv_read16(G->c[i], LOGBASE + 16 * k + off); }
static void open_gate(struct Grp* G) { for (int i = 0; i < G->n; ++i) gba_priv_write32(G->c[i], EW + 0xF4, 1); }

static int run_until(struct Grp* G, uint32_t transfers, int max_frames) {
	while (G->frames < max_frames) {
		step(G);
		int ok = 1;
		for (int i = 0; i < G->n; ++i) if (count_of(G, i) < transfers) ok = 0;
		if (ok) return 1;
	}
	return 0;
}

#define NT 24

static void normal_pair(int bits) {
	printf("[lockstep normal%d pair]\n", bits);
	const char* roms[2] = { bits == 32 ? "n32_master" : "n8_master", bits == 32 ? "n32_slave" : "n8_slave" };
	uint32_t base[2] = { 0xA5A50000u, 0x5A5A0100u };
	struct Grp G;
	grp_init(&G, 2, roms, base);
	for (int i = 0; i < 5; ++i) step(&G);
	CHECK(count_of(&G, 0) == 0 && count_of(&G, 1) == 0, "transfers before the gate opened");
	open_gate(&G);
	CHECK(run_until(&G, NT, 3000), "timeout: master=%u slave=%u", count_of(&G, 0), count_of(&G, 1));
	uint32_t mask = bits == 32 ? 0xFFFFFFFFu : 0xFFu;
	for (uint32_t k = 0; k < NT; ++k) {
		CHECK(logw(&G, 0, k, 0) == ((base[0] + k) & mask), "k=%u master sent %08X", k, logw(&G, 0, k, 0));
		CHECK(logw(&G, 1, k, 0) == ((base[1] + k) & mask), "k=%u slave sent %08X", k, logw(&G, 1, k, 0));
		CHECK(logw(&G, 1, k, 4) == logw(&G, 0, k, 0), "k=%u slave received %08X, master sent %08X", k, logw(&G, 1, k, 4), logw(&G, 0, k, 0));
		if (g_fail > 12) break;
	}
	printf("  %d frames, stalls %ld\n", G.frames, gba_group_stalls(G.g));
	CHECK(gba_group_stalls(G.g) == 0, "scheduler stalls");
	grp_free(&G);
}

static void normal_chain3(void) {
	printf("[lockstep normal32 chain, 3 players]\n");
	const char* roms[3] = { "n32_master", "n32_slave", "n32_slave" };
	uint32_t base[3] = { 0xA0000000u, 0xB0000000u, 0xC0000000u };
	struct Grp G;
	grp_init(&G, 3, roms, base);
	for (int i = 0; i < 5; ++i) step(&G);
	open_gate(&G);
	CHECK(run_until(&G, 16, 3000), "timeout");
	for (uint32_t k = 0; k < 16; ++k) {
		CHECK(logw(&G, 1, k, 4) == base[0] + k, "k=%u p1 got %08X", k, logw(&G, 1, k, 4));
		CHECK(logw(&G, 2, k, 4) == base[1] + k, "k=%u p2 got %08X", k, logw(&G, 2, k, 4));
		if (g_fail > 12) break;
	}
	grp_free(&G);
}

static void multi(const char* rom, int players, int gen3) {
	printf("[lockstep %s, %d players]\n", rom, players);
	const char* roms[4] = { rom, rom, rom, rom };
	uint32_t base[4] = { 0x1000, 0x2000, 0x3000, 0x4000 };
	struct Grp G;
	grp_init(&G, players, roms, base);
	for (int i = 0; i < 8; ++i) step(&G);
	open_gate(&G);
	CHECK(run_until(&G, NT, 3000), "timeout: counts %u %u after %d frames", count_of(&G, 0), count_of(&G, 1), G.frames);
	for (int p = 0; p < players; ++p) {
		for (uint32_t k = 0; k < NT; ++k) {
			for (int s = 0; s < 4; ++s) {
				uint16_t want = s < players ? (uint16_t) (base[s] + k) : 0xFFFF;
				uint16_t got = logh(&G, p, k, 2 * s);
				CHECK(got == want, "player %d transfer %u SIOMULTI%d = %04X, want %04X", p, k, s, got, want);
			}
			uint16_t cnt = logh(&G, p, k, 8);
			CHECK(((cnt >> 4) & 3) == (uint16_t) p, "player %d SIOCNT id bits %u (cnt=%04X)", p, (cnt >> 4) & 3, cnt);
			CHECK(((cnt >> 2) & 1) == (p > 0), "player %d SIOCNT slave bit (cnt=%04X)", p, cnt);
			CHECK(!(cnt & 0x80), "busy bit still set in IRQ (cnt=%04X)", cnt);
			CHECK(!(cnt & 0x40), "error bit set (cnt=%04X)", cnt);
			CHECK(logh(&G, p, k, 10) == (uint16_t) (base[p] + k), "player %d sent value %04X", p, logh(&G, p, k, 10));
			if (gen3) {
				uint16_t pre = logh(&G, p, k, 12);
				if (p == 0) CHECK((pre & 0xC) == 0x8, "master started with SIOCNT=%04X (SD must be 1, SI 0)", pre);
			}
			if (g_fail > 12) goto done;
		}
	}
	if (gen3) {
		for (int p = 0; p < players; ++p) {
			CHECK(gba_priv_read32(G.c[p], EW + 0x14) == 0, "player %d saw the SIOCNT error bit", p);
			CHECK(gba_priv_read32(G.c[p], EW + 0x18) == 0, "player %d saw busy set inside the SIO IRQ", p);
		}
		printf("  starts %u, master saw SD=0 %u times\n", gba_priv_read32(G.c[0], EW + 0x10), gba_priv_read32(G.c[0], EW + 0x0C));
		uint32_t t = gba_priv_read32(G.c[1], EW + 0x08);
		CHECK(t > 100, "timer IRQs %u", t);
	}
done:
	printf("  %d frames, stalls %ld\n", G.frames, gba_group_stalls(G.g));
	CHECK(gba_group_stalls(G.g) == 0, "scheduler stalls");
	grp_free(&G);
}

/* a core that joins late (still booting) and a master that waits for the gate: nothing hangs, SD only rises when all are in */
static void late_join(void) {
	printf("[lockstep gen3, slave in multiplayer mode only after 200 frames: gate closed until all ready]\n");
	const char* roms[2] = { "gen3", "gen3" };
	uint32_t base[2] = { 0x1000, 0x2000 };
	struct Grp G;
	grp_init(&G, 2, roms, base);
	open_gate(&G);
	for (int i = 0; i < 300; ++i) step(&G);
	CHECK(count_of(&G, 0) > 100, "master transfers %u", count_of(&G, 0));
	grp_free(&G);
}

static void pokesim(void) {
	printf("[lockstep pokesim, 2 players, 600 frames]\n");
	const char* roms[2] = { "pokesim", "pokesim" };
	uint32_t base[2] = { 0, 0 };
	struct Grp G;
	grp_init(&G, 2, roms, base);
	for (int i = 0; i < 600; ++i) step(&G);
	for (int p = 0; p < 2; ++p) {
		uint32_t good = gba_priv_read32(G.c[p], EW + 0), bad = gba_priv_read32(G.c[p], EW + 4), miss = gba_priv_read32(G.c[p], EW + 8);
		uint32_t err = gba_priv_read32(G.c[p], EW + 0x18), maxgap = gba_priv_read32(G.c[p], EW + 0x30);
		printf("  P%d good=%u bad=%u missed=%u error=%u maxgap=%u\n", p, good, bad, miss, err, maxgap);
		CHECK(good > 560 && bad == 0 && err == 0, "pokesim link quality");
		CHECK(miss <= 8, "missed %u", miss);
	}
	grp_free(&G);
}

/* master in Normal-32, the other side in multiplayer: nobody hangs */
static void mode_mismatch(void) {
	printf("[lockstep mode mismatch: normal32 master vs multiplayer child]\n");
	const char* roms[2] = { "n32_master", "multi" };
	uint32_t base[2] = { 0xA5A50000u, 0x2000 };
	struct Grp G;
	grp_init(&G, 2, roms, base);
	for (int i = 0; i < 5; ++i) step(&G);
	open_gate(&G);
	for (int t = 0; t < 300 && count_of(&G, 0) < 4; ++t) step(&G);
	CHECK(count_of(&G, 0) >= 4, "master hung (count %u)", count_of(&G, 0));
	CHECK(logw(&G, 0, 0, 4) == 0xFFFFFFFFu, "master received %08X", logw(&G, 0, 0, 4));
	grp_free(&G);
}

/* states with and without the lockstep trailer; a reload mid-transfer-stream must continue bit-identically */
static uint32_t crc_table[256];
static uint32_t crc_update(uint32_t crc, const uint8_t* p, size_t n) {
	crc = ~crc;
	while (n--) crc = crc_table[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
	return ~crc;
}
static uint32_t hash_all(struct Grp* G) {
	uint32_t crc = 0;
	for (int i = 0; i < G->n; ++i) {
		size_t n = gba_state_size(G->c[i]);
		uint8_t* st = malloc(n);
		if (gba_save_state(G->c[i], st, n) != n) { printf("save state failed\n"); exit(1); }
		crc = crc_update(crc, st, n);
		free(st);
	}
	return crc;
}

static void reload_equivalence(int players, int pause_every) {
	printf("[lockstep state reload equivalence, %d players, snapshots every %d frames]\n", players, pause_every);
	const char* roms[4] = { "gen3", "gen3", "gen3", "gen3" };
	uint32_t base[4] = { 0x1000, 0x2000, 0x3000, 0x4000 };
	struct Grp A, B;
	grp_init(&A, players, roms, base);
	grp_init(&B, players, roms, base);
	open_gate(&A);
	open_gate(&B);
	for (int f = 0; f < 600; ++f) {
		step(&A);
		step(&B);
		if ((f + 1) % pause_every == 0) {
			/* B: save every core, throw the cores away and continue in fresh ones loaded from the states */
			uint8_t* st[4];
			size_t sz[4];
			for (int i = 0; i < players; ++i) {
				sz[i] = gba_state_size(B.c[i]);
				st[i] = malloc(sz[i]);
				CHECK(gba_save_state(B.c[i], st[i], sz[i]) == sz[i], "save state");
			}
			grp_free(&B);
			memset(&B, 0, sizeof(B));
			B.n = players;
			B.frames = f + 1;
			for (int i = 0; i < players; ++i) {
				B.c[i] = gba_create();
				gba_load_rom(B.c[i], rompath("gen3"));
				CHECK(gba_load_state(B.c[i], st[i], sz[i]) == 0, "load state");
				free(st[i]);
			}
			B.g = gba_group_create(players);
			for (int i = 0; i < players; ++i) CHECK(gba_group_add(B.g, B.c[i]) == 0, "add");
			uint32_t ha = hash_all(&A), hb = hash_all(&B);
			CHECK(ha == hb, "hash differs right after reload at frame %d: %08x vs %08x", f + 1, ha, hb);
		}
	}
	uint32_t ha = hash_all(&A), hb = hash_all(&B);
	printf("  final %08x %08x, transfers %u\n", ha, hb, count_of(&A, 0));
	CHECK(ha == hb, "states diverged after reloads");
	grp_free(&A);
	grp_free(&B);
}

/* A grouped core that is dissolved gets its classic driver back and can use the old API again. */
static void dissolve(void) {
	printf("[group dissolve: classic mode afterwards]\n");
	const char* roms[2] = { "multi", "multi" };
	uint32_t base[2] = { 0x1000, 0x2000 };
	struct Grp G;
	grp_init(&G, 2, roms, base);
	for (int i = 0; i < 10; ++i) step(&G);
	CHECK(gba_run_frame(G.c[0]) == GBA_RUN_ERROR, "gba_run_frame must refuse grouped cores");
	gba_group_destroy(G.g);
	G.g = NULL;
	CHECK(gba_run_frame(G.c[0]) == GBA_RUN_FRAME_DONE, "run after dissolve");
	CHECK(gba_run_frame(G.c[1]) == GBA_RUN_FRAME_DONE, "run after dissolve");
	gba_link_configure(G.c[0], 0, 1);
	for (int i = 0; i < 5; ++i) CHECK(gba_run_frame(G.c[0]) == GBA_RUN_FRAME_DONE, "unplugged run");
	for (int i = 0; i < 2; ++i) gba_destroy(G.c[i]);
	/* destroying a member first must not crash the others */
	grp_init(&G, 2, roms, base);
	for (int i = 0; i < 5; ++i) step(&G);
	gba_destroy(G.c[1]);
	CHECK(gba_group_run_frame(G.g) == GBA_RUN_ERROR, "dissolved group must refuse to run");
	gba_group_destroy(G.g);
	gba_destroy(G.c[0]);
}

int main(int argc, char** argv) {
	if (argc < 2) { fprintf(stderr, "usage: %s romdir\n", argv[0]); return 2; }
	g_romdir = argv[1];
	for (uint32_t i = 0; i < 256; ++i) {
		uint32_t c = i;
		for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		crc_table[i] = c;
	}
	normal_pair(32);
	normal_pair(8);
	normal_chain3();
	multi("multi", 2, 0);
	multi("multi", 3, 0);
	multi("multi", 4, 0);
	multi("gen3", 2, 1);
	multi("gen3", 3, 1);
	multi("gen3", 4, 1);
	late_join();
	pokesim();
	mode_mismatch();
	reload_equivalence(2, 37);
	reload_equivalence(3, 101);
	dissolve();
	printf("%s\n", g_fail ? "RESULT: FAIL" : "RESULT: PASS");
	return g_fail ? 1 : 0;
}
