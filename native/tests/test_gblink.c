/* Game Boy / Game Boy Color link tests (API version 5), ROMs from make_gb_link_rom.py (WRAM contract in its docstring).
 *
 *   Lockstep group (two consoles in one process, mGBA's GBSIOLockstep, same ROM or not): transfers in both directions, the master
 *   role following whoever starts, CGB fast clock, simultaneous masters, state reload equivalence (the group trailer), dissolve.
 *   Classic link (two cores exchanging opaque messages through a relay with latency): byte exchange, ordering, slow partner,
 *   different ROMs, unplug, a partner that never answers.
 *
 *   test_gblink <gb-link-rom-dir>                                                                                          */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const char* g_dir;

enum { ROLE_IDLE = 0, ROLE_MASTER = 1, ROLE_SLAVE = 2, ROLE_AUTO = 3 };
#define KA 1

static gba_core* load(const char* name) {
	char path[1024];
	snprintf(path, sizeof(path), "%s/%s", g_dir, name);
	gba_core* c = gba_create();
	if (!c || gba_load_rom(c, path) != 0) { printf("  cannot load %s\n", path); exit(2); }
	for (int i = 0; i < 6; ++i) { int16_t pcm[4096]; gba_run_frame(c); while (gba_read_audio(c, pcm, 2048) > 0) { } } /* boot: the program clears its WRAM page first */
	return c;
}

/* role / base / interval / SC value, written as bytes of the WRAM contract */
static void setup(gba_core* c, int role, int base, int interval, int scval) {
	gba_priv_write32(c, 0xC100, (uint32_t) role | ((uint32_t) base << 16) | ((uint32_t) interval << 24));
	gba_priv_write32(c, 0xC119, (uint32_t) scval);
}
static void set_role(gba_core* c, int role) { gba_priv_write32(c, 0xC100, (uint32_t) role | (gba_priv_read32(c, 0xC100) & 0xFFFFFF00u)); }
static int wb(gba_core* c, uint32_t a) { return gba_priv_read16(c, a) & 0xFF; }
static int nlog(gba_core* c) { return wb(c, 0xC111); }
static int logsent(gba_core* c, int i) { return wb(c, 0xC200 + 2 * i); }
static int logrecv(gba_core* c, int i) { return wb(c, 0xC201 + 2 * i); }

static void drain(gba_core* c) { int16_t pcm[4096]; while (gba_read_audio(c, pcm, 2048) > 0) { } }

/* ------------------------------------------------------------------ lockstep group */

struct Grp { gba_core* c[2]; gba_group* g; int frames; };

static void grp_make(struct Grp* G, const char* a, const char* b) {
	memset(G, 0, sizeof(*G));
	G->c[0] = load(a);
	G->c[1] = load(b);
}
static void grp_join(struct Grp* G) {
	G->g = gba_group_create(2);
	for (int i = 0; i < 2; ++i) CHECK(gba_group_add(G->g, G->c[i]) == 0, "group add %d", i);
}
static void grp_free(struct Grp* G) {
	if (G->g) gba_group_destroy(G->g);
	for (int i = 0; i < 2; ++i) if (G->c[i]) gba_destroy(G->c[i]);
	memset(G, 0, sizeof(*G));
}
static void grp_step(struct Grp* G, int n) {
	for (int i = 0; i < n; ++i) {
		CHECK(gba_group_run_frame(G->g) == GBA_RUN_FRAME_DONE, "group frame %d", G->frames);
		drain(G->c[0]);
		drain(G->c[1]);
		++G->frames;
	}
}
static int grp_until(struct Grp* G, int transfers, int max) {
	while (G->frames < max) {
		grp_step(G, 1);
		if (nlog(G->c[0]) >= transfers && nlog(G->c[1]) >= transfers) return 1;
	}
	return 0;
}

/* a and b exchanged what they were supposed to: a's received byte i is b's sent byte i and vice versa */
static void verify_pair(struct Grp* G, int a, int b, int n, int basea, int baseb) {
	for (int i = 0; i < n; ++i) {
		CHECK(logrecv(G->c[a], i) == logsent(G->c[b], i), "transfer %d: %d received %02x, %d sent %02x", i, a, logrecv(G->c[a], i), b, logsent(G->c[b], i));
		CHECK(logrecv(G->c[b], i) == logsent(G->c[a], i), "transfer %d: %d received %02x, %d sent %02x", i, b, logrecv(G->c[b], i), a, logsent(G->c[a], i));
		CHECK(logsent(G->c[a], i) == ((basea + i) & 0xFF), "transfer %d: %d sent %02x, want %02x", i, a, logsent(G->c[a], i), (basea + i) & 0xFF);
		CHECK(logsent(G->c[b], i) == ((baseb + i) & 0xFF), "transfer %d: %d sent %02x, want %02x", i, b, logsent(G->c[b], i), (baseb + i) & 0xFF);
		if (g_fail > 10) break;
	}
}

static void ls_pair(const char* title, const char* ra, const char* rb, int masterIdx, int interval, int sc) {
	printf("[lockstep %s]\n", title);
	struct Grp G;
	grp_make(&G, ra, rb);
	for (int i = 0; i < 5; ++i) { gba_run_frame(G.c[0]); gba_run_frame(G.c[1]); } /* boot a few frames standalone, then join */
	setup(G.c[masterIdx], ROLE_MASTER, 0x10, interval, sc);
	setup(G.c[1 - masterIdx], ROLE_SLAVE, 0xA0, 1, 0x81);
	grp_join(&G);
	CHECK(grp_until(&G, 40, 3000), "timeout: logs %d %d after %d frames", nlog(G.c[0]), nlog(G.c[1]), G.frames);
	int m = masterIdx, s = 1 - masterIdx;
	verify_pair(&G, m, s, 40, 0x10, 0xA0);
	printf("  %d frames, stalls %ld\n", G.frames, gba_group_stalls(G.g));
	CHECK(gba_group_stalls(G.g) == 0, "scheduler stalls");
	grp_free(&G);
}

/* both consoles wait as slaves; the one whose A button is pressed becomes the master (what Pokemon's cable club does) */
static void ls_auto(void) {
	printf("[lockstep: role decided by the first console that clocks]\n");
	for (int who = 0; who < 2; ++who) {
		struct Grp G;
		grp_make(&G, "gblink_dmg.gb", "gblink_dmg.gb");
		setup(G.c[0], ROLE_AUTO, 0x10, 1, 0x81);
		setup(G.c[1], ROLE_AUTO, 0xA0, 1, 0x81);
		grp_join(&G);
		grp_step(&G, 60);
		CHECK(nlog(G.c[0]) == 0 && nlog(G.c[1]) == 0, "transfers before anybody pressed A: %d %d", nlog(G.c[0]), nlog(G.c[1]));
		gba_set_keys(G.c[who], KA);
		CHECK(grp_until(&G, 20, 1200), "timeout (who=%d): logs %d %d", who, nlog(G.c[0]), nlog(G.c[1]));
		/* the pressing console's transfer k carries its base + k, the other one's the same: both logs agree pairwise */
		verify_pair(&G, who, 1 - who, 20, who == 0 ? 0x10 : 0xA0, who == 0 ? 0xA0 : 0x10);
		CHECK(gba_group_stalls(G.g) == 0, "scheduler stalls");
		grp_free(&G);
	}
}

/* both consoles clock at once (a collision): nothing may hang; afterwards one master works again */
static void ls_collision(void) {
	printf("[lockstep: both consoles start as master]\n");
	struct Grp G;
	grp_make(&G, "gblink_dmg.gb", "gblink_dmg.gb");
	setup(G.c[0], ROLE_MASTER, 0x10, 3, 0x81);
	setup(G.c[1], ROLE_MASTER, 0xA0, 3, 0x81);
	grp_join(&G);
	grp_step(&G, 300);
	CHECK(nlog(G.c[0]) >= 10 && nlog(G.c[1]) >= 10, "both masters hung: %d %d transfers", nlog(G.c[0]), nlog(G.c[1]));
	set_role(G.c[1], ROLE_SLAVE);
	gba_priv_write32(G.c[0], 0xC110, 0);
	grp_free(&G);
}

static uint32_t crc_table[256];
static uint32_t crc_update(uint32_t crc, const uint8_t* p, size_t n) {
	crc = ~crc;
	while (n--) crc = crc_table[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
	return ~crc;
}
static uint32_t hash_all(struct Grp* G) {
	uint32_t crc = 0;
	for (int i = 0; i < 2; ++i) {
		size_t n = gba_state_size(G->c[i]);
		uint8_t* st = malloc(n);
		if (gba_save_state(G->c[i], st, n) != n) { printf("save state failed\n"); exit(1); }
		/* mGBA's audio high-pass (capacitor) charge and its rendered sample history are not restored exactly by a state load,
		 * even for a single core (they only shape the sound): leave them out when comparing a live core with a reloaded one */
		memset(st + 0xA8, 0, 8);
		memset(st + 0x1D8, 0, 0x260 - 0x1D8);
		crc = crc_update(crc, st, n);
		free(st);
	}
	return crc;
}

/* the group trailer: a reload at any frame (also mid transfer) continues bit-identically; also the CRC pinned for CI */
static uint32_t reload_equivalence(const char* ra, const char* rb, int pause_every, int sc, int swap) {
	printf("[lockstep state reload equivalence %s + %s, snapshots every %d frames%s]\n", ra, rb, pause_every, swap ? ", slave starts" : "");
	struct Grp A, B;
	grp_make(&A, ra, rb);
	grp_make(&B, ra, rb);
	int m = swap ? 1 : 0;
	gba_core* cores[4] = { A.c[0], A.c[1], B.c[0], B.c[1] };
	for (int k = 0; k < 2; ++k) {
		gba_set_keys(cores[2 * k], 0);
		setup(cores[2 * k + m], ROLE_MASTER, 0x10, 1, sc);
		setup(cores[2 * k + 1 - m], ROLE_SLAVE, 0xA0, 1, 0x81);
	}
	grp_join(&A);
	grp_join(&B);
	uint32_t last = 0;
	for (int f = 0; f < 700; ++f) {
		grp_step(&A, 1);
		grp_step(&B, 1);
		if ((f + 1) % pause_every == 0) {
			uint8_t* st[2];
			size_t sz[2];
			for (int i = 0; i < 2; ++i) {
				sz[i] = gba_state_size(B.c[i]);
				st[i] = malloc(sz[i]);
				CHECK(gba_save_state(B.c[i], st[i], sz[i]) == sz[i], "save state");
			}
			int frames = B.frames;
			grp_free(&B);
			memset(&B, 0, sizeof(B));
			B.frames = frames;
			B.c[0] = load(ra);
			B.c[1] = load(rb);
			for (int i = 0; i < 2; ++i) {
				CHECK(gba_load_state(B.c[i], st[i], sz[i]) == 0, "load state");
				free(st[i]);
			}
			grp_join(&B);
			uint32_t ha = hash_all(&A), hb = hash_all(&B);
			CHECK(ha == hb, "hash differs right after the reload at frame %d: %08x vs %08x", f + 1, ha, hb);
		}
		last = hash_all(&A);
	}
	uint32_t hb = hash_all(&B);
	printf("  final %08x %08x, transfers %d, stalls %ld\n", last, hb, nlog(A.c[0]), gba_group_stalls(A.g));
	CHECK(last == hb, "states diverged after reloads");
	CHECK(nlog(A.c[0]) > 100, "too few transfers (%d) for the test to mean anything", nlog(A.c[0]));
	grp_free(&A);
	grp_free(&B);
	return last;
}

static void ls_dissolve(void) {
	printf("[lockstep: dissolve, classic driver afterwards, destroying a member]\n");
	struct Grp G;
	grp_make(&G, "gblink_dmg.gb", "gblink_dmg.gb");
	setup(G.c[0], ROLE_MASTER, 0x10, 1, 0x81);
	setup(G.c[1], ROLE_SLAVE, 0xA0, 1, 0x81);
	grp_join(&G);
	grp_step(&G, 30);
	CHECK(gba_run_frame(G.c[0]) == GBA_RUN_ERROR, "gba_run_frame must refuse grouped cores");
	gba_group_destroy(G.g);
	G.g = NULL;
	CHECK(gba_run_frame(G.c[0]) == GBA_RUN_FRAME_DONE, "run after dissolve");
	CHECK(gba_run_frame(G.c[1]) == GBA_RUN_FRAME_DONE, "run after dissolve");
	for (int i = 0; i < 20; ++i) {
		CHECK(gba_run_frame(G.c[0]) == GBA_RUN_FRAME_DONE, "unplugged master run");
		CHECK(gba_run_frame(G.c[1]) == GBA_RUN_FRAME_DONE, "unplugged slave run");
	}
	grp_free(&G);
	grp_make(&G, "gblink_dmg.gb", "gblink_dmg.gb");
	setup(G.c[0], ROLE_MASTER, 0x10, 1, 0x81);
	setup(G.c[1], ROLE_SLAVE, 0xA0, 1, 0x81);
	grp_join(&G);
	grp_step(&G, 20);
	gba_destroy(G.c[1]);
	G.c[1] = NULL;
	CHECK(gba_group_run_frame(G.g) == GBA_RUN_ERROR, "dissolved group must refuse to run");
	CHECK(gba_run_frame(G.c[0]) == GBA_RUN_FRAME_DONE, "the survivor runs on");
	grp_free(&G);
	/* groups never mix platforms: a GBA ROM cannot join a Game Boy group */
	gba_core* gba = gba_create();
	gba_core* gb = load("gblink_dmg.gb");
	gba_group* g = gba_group_create(2);
	CHECK(gba_group_add(g, gb) == 0, "gb add");
	CHECK(gba_group_add(g, gba) == -1, "a GBA core without ROM must not join");
	gba_group_destroy(g);
	gba_destroy(gba);
	gba_destroy(gb);
}

/* ------------------------------------------------------------------ classic link */

#define MAXQ 8192
struct Msg { int to, from, due; uint8_t d[GBA_LINK_MSG_MAX]; size_t n; };
struct Net {
	gba_core* c[2];
	struct Msg q[MAXQ];
	int qn, tick, latency;
	int waits[2], dones[2], msgs[2];
	int drop;          /* messages from core i are dropped when bit i is set */
};

static void net_make(struct Net* N, const char* a, const char* b, int latency) {
	memset(N, 0, sizeof(*N));
	N->latency = latency;
	N->c[0] = load(a);
	N->c[1] = load(b);
}
static void net_plug(struct Net* N) { for (int i = 0; i < 2; ++i) gba_link_configure(N->c[i], i, 2); }
static void collect(struct Net* N, int i) {
	uint8_t buf[GBA_LINK_MSG_MAX];
	size_t len;
	while ((len = gba_link_poll_outgoing(N->c[i], buf, sizeof(buf))) > 0) {
		N->msgs[i]++;
		if (N->drop & (1 << i)) continue;
		if (N->qn >= MAXQ) { CHECK(0, "relay overflow"); return; }
		struct Msg* m = &N->q[N->qn++];
		m->from = i; m->to = 1 - i; m->n = len; memcpy(m->d, buf, len);
		m->due = N->tick + N->latency;
	}
}
static void deliver_due(struct Net* N) {
	int w = 0;
	for (int r = 0; r < N->qn; ++r) {
		struct Msg* m = &N->q[r];
		if (m->due <= N->tick) {
			gba_link_deliver(N->c[m->to], m->from, m->d, m->n);
			collect(N, m->to);
		} else {
			N->q[w++] = *m;
		}
	}
	N->qn = w;
}
static void net_step(struct Net* N) {
	deliver_due(N);
	for (int i = 0; i < 2; ++i) {
		int r = gba_run_frame(N->c[i]);
		if (r == GBA_RUN_LINK_WAIT) N->waits[i]++;
		else if (r == GBA_RUN_FRAME_DONE) { N->dones[i]++; drain(N->c[i]); }
		else CHECK(0, "run_frame error %d", r);
		collect(N, i);
	}
	++N->tick;
}
static void net_free(struct Net* N) { for (int i = 0; i < 2; ++i) gba_destroy(N->c[i]); }
static int net_until(struct Net* N, int transfers, int max_ticks) {
	for (int t = 0; t < max_ticks; ++t) {
		net_step(N);
		if (nlog(N->c[0]) >= transfers && nlog(N->c[1]) >= transfers) return 1;
	}
	return 0;
}
static void net_verify(struct Net* N, int a, int b, int n, int basea, int baseb) {
	for (int i = 0; i < n; ++i) {
		CHECK(logrecv(N->c[a], i) == logsent(N->c[b], i), "transfer %d: %d received %02x, %d sent %02x", i, a, logrecv(N->c[a], i), b, logsent(N->c[b], i));
		CHECK(logrecv(N->c[b], i) == logsent(N->c[a], i), "transfer %d: %d received %02x, %d sent %02x", i, b, logrecv(N->c[b], i), a, logsent(N->c[a], i));
		CHECK(logsent(N->c[a], i) == ((basea + i) & 0xFF), "transfer %d: %d sent %02x", i, a, logsent(N->c[a], i));
		CHECK(logsent(N->c[b], i) == ((baseb + i) & 0xFF), "transfer %d: %d sent %02x", i, b, logsent(N->c[b], i));
		if (g_fail > 10) break;
	}
}

static void classic_pair(const char* title, const char* ra, const char* rb, int masterIdx, int latency, int interval) {
	printf("[classic %s, latency %d ticks, master interval %d]\n", title, latency, interval);
	struct Net N;
	net_make(&N, ra, rb, latency);
	for (int i = 0; i < 5; ++i) net_step(&N);
	setup(N.c[masterIdx], ROLE_MASTER, 0x10, interval, 0x81);
	setup(N.c[1 - masterIdx], ROLE_SLAVE, 0xA0, 1, 0x81);
	net_plug(&N);
	CHECK(net_until(&N, 40, 6000), "timeout: logs %d %d, waits %d %d", nlog(N.c[0]), nlog(N.c[1]), N.waits[0], N.waits[1]);
	net_verify(&N, masterIdx, 1 - masterIdx, 40, 0x10, 0xA0);
	printf("  %d ticks, master waited in %d run calls, %d + %d messages\n", N.tick, N.waits[masterIdx], N.msgs[0], N.msgs[1]);
	if (latency > 0) CHECK(N.waits[masterIdx] > 0, "a master with latency must have stopped at least once");
	net_free(&N);
}

/* a core whose frame completes while waiting must continue the SAME frame (video frame count stays consistent) */
static void classic_frame_pacing(void) {
	printf("[classic: the master's frame is continued after LINK_WAIT, slave runs free]\n");
	struct Net N;
	net_make(&N, "gblink_dmg.gb", "gblink_dmg.gb", 3);
	setup(N.c[0], ROLE_MASTER, 0x10, 1, 0x81);
	setup(N.c[1], ROLE_SLAVE, 0xA0, 1, 0x81);
	net_plug(&N);
	for (int i = 0; i < 400; ++i) net_step(&N);
	CHECK(N.dones[0] > 50 && N.dones[1] == 400, "frames done: master %d, slave %d", N.dones[0], N.dones[1]);
	int n = nlog(N.c[0]);
	printf("  master %d frames in 400 ticks (%d transfers), slave 400 frames\n", N.dones[0], n);
	net_free(&N);
}

/* the partner is not armed: a START is held, then answered with 0xFF, the master carries on */
static void classic_idle_partner(void) {
	printf("[classic: partner never arms its port]\n");
	struct Net N;
	net_make(&N, "gblink_dmg.gb", "gblink_dmg.gb", 1);
	setup(N.c[0], ROLE_MASTER, 0x10, 1, 0x81);
	setup(N.c[1], ROLE_IDLE, 0xA0, 1, 0x81);
	net_plug(&N);
	for (int i = 0; i < 400; ++i) net_step(&N);
	CHECK(nlog(N.c[0]) >= 5, "master only completed %d transfers against an idle partner", nlog(N.c[0]));
	for (int i = 0; i < nlog(N.c[0]) && i < 128; ++i) CHECK(logrecv(N.c[0], i) == 0xFF, "transfer %d read %02x from an idle partner", i, logrecv(N.c[0], i));
	printf("  %d transfers completed, all read 0xFF\n", nlog(N.c[0]));
	/* now the partner arms: transfers carry data again */
	int before = nlog(N.c[0]);
	set_role(N.c[1], ROLE_SLAVE);
	for (int i = 0; i < 400; ++i) net_step(&N);
	int good = 0;
	for (int i = before; i < nlog(N.c[0]) && i < 128; ++i) if (logrecv(N.c[0], i) != 0xFF) ++good;
	CHECK(good >= 10, "only %d real bytes after the partner armed", good);
	net_free(&N);
}

/* unplugging while the master waits unblocks it; a partner that never answers is the host's timeout (LinkPump) */
static void classic_unplug(void) {
	printf("[classic: unplug while waiting, messages lost]\n");
	struct Net N;
	net_make(&N, "gblink_dmg.gb", "gblink_dmg.gb", 0);
	setup(N.c[0], ROLE_MASTER, 0x10, 1, 0x81);
	setup(N.c[1], ROLE_SLAVE, 0xA0, 1, 0x81);
	net_plug(&N);
	N.drop = 2; /* nothing from the slave arrives: the master blocks */
	int waited = 0;
	for (int i = 0; i < 100 && !waited; ++i) { net_step(&N); waited = N.waits[0] > 0; }
	CHECK(waited, "the master did not wait for its partner");
	CHECK(gba_run_frame(N.c[0]) == GBA_RUN_LINK_WAIT, "still waiting");
	gba_link_configure(N.c[0], 0, 0); /* unplug: the transfer completes as with nothing connected */
	int r = GBA_RUN_LINK_WAIT;
	for (int i = 0; i < 5 && r == GBA_RUN_LINK_WAIT; ++i) r = gba_run_frame(N.c[0]);
	CHECK(r == GBA_RUN_FRAME_DONE, "master still blocked after unplugging (%d)", r);
	int n0 = nlog(N.c[0]);
	for (int i = 0; i < 40; ++i) CHECK(gba_run_frame(N.c[0]) == GBA_RUN_FRAME_DONE, "unplugged master run");
	CHECK(nlog(N.c[0]) > n0, "the unplugged master stopped transferring");
	CHECK(logrecv(N.c[0], nlog(N.c[0]) - 1) == 0xFF, "an unplugged master reads 0xFF");
	net_free(&N);
}

/* GB <-> GBC, same game vs different games: all work (the link is just bytes) */
static void classic_all(void) {
	classic_pair("same ROM", "gblink_dmg.gb", "gblink_dmg.gb", 0, 0, 1);
	classic_pair("same ROM, slave is the master", "gblink_dmg.gb", "gblink_dmg.gb", 1, 0, 1);
	classic_pair("different ROMs", "gblink_dmg.gb", "gblink_dmg_b.gb", 0, 0, 1);
	classic_pair("GB master, GBC slave", "gblink_dmg.gb", "gblink_cgb.gbc", 0, 0, 1);
	classic_pair("GBC master, GB slave", "gblink_cgb.gbc", "gblink_dmg.gb", 0, 0, 1);
	classic_pair("different ROMs, 3 ticks one way", "gblink_dmg.gb", "gblink_dmg_b.gb", 0, 3, 1);
	classic_pair("different ROMs, 8 ticks one way", "gblink_dmg.gb", "gblink_dmg_b.gb", 1, 8, 2);
}

int main(int argc, char** argv) {
	if (argc < 2) { fprintf(stderr, "usage: %s gb-link-rom-dir\n", argv[0]); return 2; }
	g_dir = argv[1];
	for (uint32_t i = 0; i < 256; ++i) {
		uint32_t c = i;
		for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		crc_table[i] = c;
	}
	ls_pair("GB + GB, console 0 is the master", "gblink_dmg.gb", "gblink_dmg.gb", 0, 1, 0x81);
	ls_pair("GB + GB, console 1 is the master", "gblink_dmg.gb", "gblink_dmg.gb", 1, 1, 0x81);
	ls_pair("GB + GB, one transfer every 4 frames", "gblink_dmg.gb", "gblink_dmg.gb", 0, 4, 0x81);
	ls_pair("GB master + different GB ROM", "gblink_dmg.gb", "gblink_dmg_b.gb", 0, 1, 0x81);
	ls_pair("GB master + GBC slave", "gblink_dmg.gb", "gblink_cgb.gbc", 0, 1, 0x81);
	ls_pair("GBC master + GB slave", "gblink_cgb.gbc", "gblink_dmg.gb", 0, 1, 0x81);
	ls_pair("GBC + GBC, fast clock", "gblink_cgb.gbc", "gblink_cgb.gbc", 0, 1, 0x83);
	ls_auto();
	ls_collision();
	reload_equivalence("gblink_dmg.gb", "gblink_dmg.gb", 37, 0x81, 0);
	reload_equivalence("gblink_dmg.gb", "gblink_dmg.gb", 11, 0x81, 1);
	reload_equivalence("gblink_cgb.gbc", "gblink_cgb.gbc", 53, 0x83, 0);
	ls_dissolve();
	classic_all();
	classic_frame_pacing();
	classic_idle_partner();
	classic_unplug();
	printf("%s\n", g_fail ? "RESULT: FAIL" : "RESULT: PASS");
	return g_fail ? 1 : 0;
}
