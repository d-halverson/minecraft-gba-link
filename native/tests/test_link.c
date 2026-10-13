/* Link cable tests: several cores in one process, exchanging opaque messages through an
 * in-memory relay with configurable latency (measured in "ticks": one gba_run_frame per core). */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const char* g_romdir;

#define EW 0x02000000u
#define LOGBASE (EW + 0x100)

/* ---------------- relay ---------------- */

#define MAXQ 4096
struct Msg { int from, to; uint8_t d[GBA_LINK_MSG_MAX]; size_t n; int due; };

struct Net {
	gba_core* c[4];
	int n;
	int tick;
	int latency;
	struct Msg q[MAXQ];
	int qn;
	int waits[4];       /* number of LINK_WAIT results per core */
	int dones[4];       /* number of FRAME_DONE results per core */
	int msgs[4];        /* messages emitted per core */
	int bad_vcount;     /* FRAME_DONE where the video line was not at the end of the visible area */
	int drop;           /* when set, messages are discarded instead of relayed */
};

static char* rompath(const char* name) {
	static char buf[4][1024];
	static int i;
	i = (i + 1) % 4;
	snprintf(buf[i], sizeof(buf[i]), "%s/link_%s.gba", g_romdir, name);
	return buf[i];
}

static void net_init(struct Net* net, int n, const char** roms, int latency, const uint32_t* bases) {
	memset(net, 0, sizeof(*net));
	net->n = n;
	net->latency = latency;
	for (int i = 0; i < n; ++i) {
		net->c[i] = gba_create();
		if (!net->c[i] || gba_load_rom(net->c[i], rompath(roms[i])) != 0) {
			printf("  cannot load %s\n", rompath(roms[i]));
			exit(2);
		}
		gba_priv_write32(net->c[i], EW + 0xF0, bases[i]);
		gba_priv_write32(net->c[i], EW + 0xF4, 0);
		gba_link_configure(net->c[i], i, n);
	}
}

static void net_free(struct Net* net) {
	for (int i = 0; i < net->n; ++i) { gba_destroy(net->c[i]); }
}

static void collect(struct Net* net, int i) {
	uint8_t buf[GBA_LINK_MSG_MAX];
	size_t len;
	while ((len = gba_link_poll_outgoing(net->c[i], buf, sizeof(buf))) > 0) {
		CHECK(len <= GBA_LINK_MSG_MAX, "message too long");
		net->msgs[i]++;
		if (net->drop) { continue; }
		for (int j = 0; j < net->n; ++j) {
			if (j == i) { continue; }
			if (net->qn >= MAXQ) { CHECK(0, "relay queue overflow"); return; }
			struct Msg* m = &net->q[net->qn++];
			m->from = i; m->to = j; m->n = len; memcpy(m->d, buf, len);
			m->due = net->tick + net->latency;
		}
	}
}

static void deliver_due(struct Net* net) {
	/* deliver in FIFO order everything due at or before this tick */
	int w = 0;
	for (int r = 0; r < net->qn; ++r) {
		struct Msg* m = &net->q[r];
		if (m->due <= net->tick) {
			gba_link_deliver(net->c[m->to], m->from, m->d, m->n);
			collect(net, m->to); /* the host polls right after every deliver */
		} else {
			net->q[w++] = *m;
		}
	}
	net->qn = w;
}

static void step(struct Net* net) {
	deliver_due(net);
	for (int i = 0; i < net->n; ++i) {
		int r = gba_run_frame(net->c[i]);
		if (r == GBA_RUN_LINK_WAIT) {
			net->waits[i]++;
		} else if (r == GBA_RUN_FRAME_DONE) {
			net->dones[i]++;
			/* the frame must end at the start of VBlank (VCOUNT 160), even after link stops */
			uint16_t vc = gba_priv_read16(net->c[i], 0x04000006);
			if (vc < 159 || vc > 161) { net->bad_vcount++; }
		} else {
			CHECK(0, "run_frame error %d", r);
		}
		collect(net, i);
	}
	net->tick++;
}

static void open_gate(struct Net* net) {
	for (int i = 0; i < net->n; ++i) { gba_priv_write32(net->c[i], EW + 0xF4, 1); }
}

static uint32_t count_of(struct Net* net, int i) { return gba_priv_read32(net->c[i], EW); }

static int run_until(struct Net* net, uint32_t transfers, int max_ticks) {
	while (net->tick < max_ticks) {
		step(net);
		int ok = 1;
		for (int i = 0; i < net->n; ++i) { if (count_of(net, i) < transfers) { ok = 0; } }
		if (ok) { return 1; }
	}
	return 0;
}

static uint32_t logw(struct Net* net, int i, uint32_t k, uint32_t off) {
	return gba_priv_read32(net->c[i], LOGBASE + 16 * k + off);
}
static uint16_t logh(struct Net* net, int i, uint32_t k, uint32_t off) {
	return gba_priv_read16(net->c[i], LOGBASE + 16 * k + off);
}

/* ---------------- scenarios ---------------- */

#define N 24 /* transfers checked */

static void normal_pair(const char* tag, int bits, int latency) {
	printf("[%s latency=%d]\n", tag, latency);
	const char* roms[2] = { bits == 32 ? "n32_master" : "n8_master", bits == 32 ? "n32_slave" : "n8_slave" };
	uint32_t base[2] = { 0xA5A50000u, 0x5A5A0100u };
	struct Net net;
	net_init(&net, 2, roms, latency, base);
	for (int i = 0; i < 5; ++i) { step(&net); }   /* let both ROMs boot and arm their ports */
	CHECK(count_of(&net, 0) == 0 && count_of(&net, 1) == 0, "transfers happened before the gate opened");
	open_gate(&net);
	CHECK(run_until(&net, N, 3000), "timeout: master=%u slave=%u after %d ticks (waits %d)",
	      count_of(&net, 0), count_of(&net, 1), net.tick, net.waits[0]);
	uint32_t mask = bits == 32 ? 0xFFFFFFFFu : 0xFFu;
	for (uint32_t k = 0; k < N; ++k) {
		uint32_t ms = logw(&net, 0, k, 0), mr = logw(&net, 0, k, 4);
		uint32_t ss = logw(&net, 1, k, 0), sr = logw(&net, 1, k, 4);
		CHECK(ms == ((base[0] + k) & mask), "k=%u master sent %08X", k, ms);
		CHECK(ss == ((base[1] + k) & mask), "k=%u slave sent %08X", k, ss);
		CHECK(mr == ss, "k=%u master received %08X, slave sent %08X", k, mr, ss);
		CHECK(sr == ms, "k=%u slave received %08X, master sent %08X", k, sr, ms);
		if (g_fail > 12) { break; }
	}
	printf("  %d ticks, master LINK_WAITs=%d, msgs master=%d slave=%d, bad-vcount frames=%d\n",
	       net.tick, net.waits[0], net.msgs[0], net.msgs[1], net.bad_vcount);
	if (latency > 0) { CHECK(net.waits[0] > 0, "master never returned LINK_WAIT"); }
	CHECK(net.waits[1] == 0, "child must never wait");
	CHECK(net.bad_vcount == 0, "frames did not end at VBlank after link waits");
	net_free(&net);
}

static void normal_chain3(void) {
	printf("[normal32 daisy chain, 3 players, latency=2]\n");
	const char* roms[3] = { "n32_master", "n32_slave", "n32_slave" };
	uint32_t base[3] = { 0xA0000000u, 0xB0000000u, 0xC0000000u };
	struct Net net;
	net_init(&net, 3, roms, 2, base);
	for (int i = 0; i < 5; ++i) { step(&net); }
	open_gate(&net);
	CHECK(run_until(&net, 16, 3000), "timeout");
	for (uint32_t k = 0; k < 16; ++k) {
		CHECK(logw(&net, 0, k, 4) == base[2] + k, "k=%u master got %08X, expected last player's %08X", k, logw(&net, 0, k, 4), base[2] + k);
		CHECK(logw(&net, 1, k, 4) == base[0] + k, "k=%u p1 got %08X", k, logw(&net, 1, k, 4));
		CHECK(logw(&net, 2, k, 4) == base[1] + k, "k=%u p2 got %08X", k, logw(&net, 2, k, 4));
		if (g_fail > 12) { break; }
	}
	net_free(&net);
}

static void multi(int players, int latency) {
	printf("[multiplayer players=%d latency=%d]\n", players, latency);
	const char* roms[4] = { "multi", "multi", "multi", "multi" };
	uint32_t base[4] = { 0x1000, 0x2000, 0x3000, 0x4000 };
	struct Net net;
	net_init(&net, players, roms, latency, base);
	for (int i = 0; i < 6; ++i) { step(&net); }
	open_gate(&net);
	CHECK(run_until(&net, N, 3000), "timeout: counts %u %u after %d ticks", count_of(&net, 0), count_of(&net, 1), net.tick);
	for (int p = 0; p < players; ++p) {
		for (uint32_t k = 0; k < N; ++k) {
			for (int s = 0; s < 4; ++s) {
				uint16_t want = s < players ? (uint16_t) (base[s] + k) : 0xFFFF;
				uint16_t got = logh(&net, p, k, 2 * s);
				CHECK(got == want, "player %d transfer %u SIOMULTI%d = %04X, want %04X", p, k, s, got, want);
			}
			uint16_t cnt = logh(&net, p, k, 8);
			CHECK(((cnt >> 4) & 3) == (uint16_t) p, "player %d SIOCNT id bits %u (cnt=%04X)", p, (cnt >> 4) & 3, cnt);
			CHECK(((cnt >> 2) & 1) == (p > 0), "player %d SIOCNT slave bit (cnt=%04X)", p, cnt);
			CHECK(!(cnt & 0x80), "busy bit still set in IRQ (cnt=%04X)", cnt);
			CHECK(logh(&net, p, k, 10) == (uint16_t) (base[p] + k), "player %d sent value %04X", p, logh(&net, p, k, 10));
			if (g_fail > 12) { goto done; }
		}
	}
done:
	printf("  %d ticks, master LINK_WAITs=%d, bad-vcount frames=%d\n", net.tick, net.waits[0], net.bad_vcount);
	CHECK(net.bad_vcount == 0, "frames did not end at VBlank");
	net_free(&net);
}

static void normal_irq(void) {
	printf("[normal32 master with SIO IRQ + slave, latency=2]\n");
	const char* roms[2] = { "n32_master_irq", "n32_slave" };
	uint32_t base[2] = { 0x11110000u, 0x22220000u };
	struct Net net;
	net_init(&net, 2, roms, 2, base);
	for (int i = 0; i < 5; ++i) { step(&net); }
	open_gate(&net);
	CHECK(run_until(&net, 16, 3000), "timeout: counts %u %u", count_of(&net, 0), count_of(&net, 1));
	for (uint32_t k = 0; k < 16; ++k) {
		CHECK(logw(&net, 0, k, 0) == base[0] + k, "k=%u master sent %08X", k, logw(&net, 0, k, 0));
		CHECK(logw(&net, 0, k, 4) == base[1] + k, "k=%u master (IRQ) received %08X", k, logw(&net, 0, k, 4));
		CHECK(logw(&net, 1, k, 4) == base[0] + k, "k=%u slave received %08X", k, logw(&net, 1, k, 4));
		if (g_fail > 12) { break; }
	}
	net_free(&net);
}

/* master sends Normal-32, but the other side is in multiplayer mode: nobody hangs, master reads all ones */
static void mode_mismatch(void) {
	printf("[mode mismatch: normal32 master vs multiplayer child]\n");
	const char* roms[2] = { "n32_master", "multi" };
	uint32_t base[2] = { 0xA5A50000u, 0x2000 };
	struct Net net;
	net_init(&net, 2, roms, 2, base);
	for (int i = 0; i < 5; ++i) { step(&net); }
	open_gate(&net);
	for (int t = 0; t < 300 && count_of(&net, 0) < 4; ++t) { step(&net); }
	CHECK(count_of(&net, 0) >= 4, "master hung (count %u)", count_of(&net, 0));
	for (uint32_t k = 0; k < 4; ++k) {
		CHECK(logw(&net, 0, k, 4) == 0xFFFFFFFFu, "k=%u master received %08X, want no-data", k, logw(&net, 0, k, 4));
	}
	net_free(&net);
}

/* counts transfer messages (type 1 = START); presence announcements (type 4) are ignored */
static int count_msgs(gba_core* c, uint8_t* first, size_t* firstlen) {
	uint8_t buf[GBA_LINK_MSG_MAX];
	int n = 0;
	size_t len;
	while ((len = gba_link_poll_outgoing(c, buf, sizeof(buf))) > 0) {
		if (buf[0] == 4) { continue; }
		if (n == 0 && first) { memcpy(first, buf, len); *firstlen = len; }
		++n;
	}
	return n;
}

static void unplug_master(const char* rom, int kind /* 0 = normal32, 1 = multi, 2 = normal8 */) {
	printf("[unplug while master waits: %s]\n", rom);
	gba_core* c = gba_create();
	if (gba_load_rom(c, rompath(rom)) != 0) { printf("  cannot load\n"); exit(2); }
	gba_priv_write32(c, EW + 0xF0, kind != 1 ? 0x1234 : 0xCAFE0001u);
	gba_link_configure(c, 0, 2);
	for (int i = 0; i < 5; ++i) { CHECK(gba_run_frame(c) == GBA_RUN_FRAME_DONE, "boot frame"); }
	gba_priv_write32(c, EW + 0xF4, 1);
	int r = GBA_RUN_FRAME_DONE;
	int tries = 0;
	while (r == GBA_RUN_FRAME_DONE && tries++ < 5) { r = gba_run_frame(c); }
	CHECK(r == GBA_RUN_LINK_WAIT, "master did not enter LINK_WAIT (r=%d)", r);
	uint8_t first[GBA_LINK_MSG_MAX]; size_t flen = 0;
	int nm = count_msgs(c, first, &flen);
	CHECK(nm == 1 && flen > 0 && flen <= GBA_LINK_MSG_MAX, "expected exactly one START message, got %d (len %zu)", nm, flen);
	/* stuck: repeated calls keep returning LINK_WAIT without progress */
	for (int i = 0; i < 5; ++i) { CHECK(gba_run_frame(c) == GBA_RUN_LINK_WAIT, "should still wait"); }
	CHECK(gba_priv_read32(c, EW) == 0, "transfer completed without a partner");
	CHECK(gba_priv_link_waiting(c), "link_waiting");
	/* cable pulled */
	gba_link_configure(c, 0, 1);
	CHECK(!gba_priv_link_waiting(c), "still waiting after unplug");
	CHECK(gba_run_frame(c) == GBA_RUN_FRAME_DONE, "frame did not finish after unplug");
	uint32_t cnt = gba_priv_read32(c, EW);
	CHECK(cnt >= 1, "no transfer completed after unplug (count %u)", cnt);
	if (kind != 1) {
		uint32_t sent = kind == 0 ? 0x1234 : 0x34, want = kind == 0 ? 0xFFFFFFFFu : 0xFF;
		CHECK(gba_priv_read32(c, LOGBASE + 0) == sent, "sent %08X", gba_priv_read32(c, LOGBASE));
		CHECK(gba_priv_read32(c, LOGBASE + 4) == want, "received %08X, want %08X", gba_priv_read32(c, LOGBASE + 4), want);
	} else {
		CHECK(gba_priv_read16(c, LOGBASE + 0) == 0x0001, "SIOMULTI0 %04X", gba_priv_read16(c, LOGBASE));
		CHECK(gba_priv_read16(c, LOGBASE + 2) == 0xFFFF, "SIOMULTI1 %04X", gba_priv_read16(c, LOGBASE + 2));
		CHECK(gba_priv_read16(c, LOGBASE + 4) == 0xFFFF, "SIOMULTI2");
		CHECK(gba_priv_read16(c, LOGBASE + 6) == 0xFFFF, "SIOMULTI3");
	}
	/* it keeps running without ever waiting again (no cable) */
	for (int i = 0; i < 20; ++i) { CHECK(gba_run_frame(c) == GBA_RUN_FRAME_DONE, "unplugged core must not wait"); }
	CHECK(count_msgs(c, NULL, NULL) == 0, "unplugged core emitted messages");
	if (kind != 1) {
		CHECK(gba_priv_read32(c, EW) > cnt, "no further transfers while unplugged");
		/* plug it back in: it must wait again */
		gba_link_configure(c, 0, 2);
		r = GBA_RUN_FRAME_DONE;
		for (tries = 0; r == GBA_RUN_FRAME_DONE && tries < 5; ++tries) { r = gba_run_frame(c); }
		CHECK(r == GBA_RUN_LINK_WAIT, "no LINK_WAIT after re-plugging");
	} else {
		/* a multiplayer game with no cable sees itself as a child (SIOCNT bit 2) and stops starting transfers */
		CHECK((gba_priv_read16(c, 0x04000128) & 4) != 0, "unplugged multiplayer port should read as child");
	}
	gba_destroy(c);
}

static void unplug_child(void) {
	printf("[unplug while child is mid-transfer / reconfigure changes]\n");
	/* master + child; drop the DONE from master so the child stays busy, then unplug the child */
	const char* roms[2] = { "n32_master", "n32_slave" };
	uint32_t base[2] = { 0xAAAA0000u, 0xBBBB0000u };
	struct Net net;
	net_init(&net, 2, roms, 1, base);
	for (int i = 0; i < 5; ++i) { step(&net); }
	open_gate(&net);
	/* run until the child has replied (so it is in the busy state) but swallow the master's reply path:
	 * simply stop delivering master-bound messages */
	for (int t = 0; t < 40 && !gba_priv_link_waiting(net.c[1]); ++t) {
		step(&net);
	}
	CHECK(gba_priv_link_waiting(net.c[1]), "child never accepted a transfer");
	/* cut the cable before DONE arrives: drop everything in flight */
	net.qn = 0;
	net.drop = 1;
	CHECK(count_of(&net, 1) == 0, "child already completed");
	gba_link_configure(net.c[1], 1, 1);
	CHECK(!gba_priv_link_waiting(net.c[1]), "child still busy after unplug");
	for (int i = 0; i < 6; ++i) { step(&net); }
	CHECK(count_of(&net, 1) >= 1, "child transfer never completed after unplug");
	CHECK(logw(&net, 1, 0, 4) == 0xFFFFFFFFu, "child received %08X, want FFFFFFFF", logw(&net, 1, 0, 4));
	/* and the master (still plugged, but nobody answers) is unblocked by its own unplug */
	gba_link_configure(net.c[0], 0, 1);
	for (int i = 0; i < 6; ++i) { step(&net); }
	CHECK(count_of(&net, 0) >= 1, "master did not recover");
	net_free(&net);
}

/* a master with a slow/lossy relay: delivering everything late still yields correct pairing */
static void high_latency(void) {
	printf("[multiplayer 2 players, latency=7 and 10 frames of jitter-free delay]\n");
	multi(2, 7);
}

/* The child's REPLY must be pollable right after gba_link_deliver(START), without running the core. */
static void instant_reply(const char* mrom, const char* crom, int multi_mode) {
	printf("[instant reply: %s master / %s child]\n", mrom, crom);
	const char* roms[2] = { mrom, crom };
	uint32_t base[2] = { 0x1000, multi_mode ? 0x2000 : 0x5A5A0100u };
	struct Net net;
	net_init(&net, 2, roms, 0, base);
	for (int i = 0; i < 6; ++i) { step(&net); }
	/* drop presence announcements */
	uint8_t buf[GBA_LINK_MSG_MAX];
	for (int i = 0; i < 2; ++i) { while (gba_link_poll_outgoing(net.c[i], buf, sizeof(buf))) {} }
	open_gate(&net);
	int r = GBA_RUN_FRAME_DONE;
	for (int t = 0; t < 5 && r != GBA_RUN_LINK_WAIT; ++t) { r = gba_run_frame(net.c[0]); }
	CHECK(r == GBA_RUN_LINK_WAIT, "master not waiting");
	size_t n = 0;
	uint8_t start[GBA_LINK_MSG_MAX];
	while ((n = gba_link_poll_outgoing(net.c[0], buf, sizeof(buf))) > 0) {
		if (buf[0] == 1) { memcpy(start, buf, n); break; }
	}
	CHECK(n > 0, "no START");
	gba_link_deliver(net.c[1], 0, start, n);
	n = gba_link_poll_outgoing(net.c[1], buf, sizeof(buf));
	CHECK(n >= 9 && buf[0] == 2 && buf[8] == 1, "no immediate REPLY after deliver (n=%zu type=%d)", n, n ? buf[0] : -1);
	if (n >= 9) {
		uint32_t d = buf[4] | buf[5] << 8 | buf[6] << 16 | (uint32_t) buf[7] << 24;
		uint32_t want = multi_mode ? (base[1] & 0xFFFF) : base[1];
		CHECK(d == want, "reply data %08X, want %08X", d, want);
		/* complete the round trip by hand: master gets the reply, answers DONE, child completes */
		gba_link_deliver(net.c[0], 1, buf, n);
		uint8_t done[GBA_LINK_MSG_MAX];
		size_t dn;
		while ((dn = gba_link_poll_outgoing(net.c[0], done, sizeof(done))) > 0 && done[0] != 3) {}
		CHECK(dn == 24 && done[0] == 3, "no DONE after reply");
		gba_link_deliver(net.c[1], 0, done, dn);
	}
	net_free(&net);
}

/* unpaced, zero-latency throughput of the multiplayer exchange (run: test_link romdir bench) */
static void bench(void) {
	const char* roms[2] = { "multi", "multi" };
	uint32_t base[2] = { 0x1000, 0x2000 };
	struct Net net;
	net_init(&net, 2, roms, 0, base);
	for (int i = 0; i < 6; ++i) { step(&net); }
	open_gate(&net);
	clock_t t0 = clock();
	run_until(&net, 2000, 100000);
	double sec = (double) (clock() - t0) / CLOCKS_PER_SEC;
	printf("bench: %u transfers in %d ticks, %.3f s -> %.0f transfers/s, %.2f ticks/transfer\n",
	       count_of(&net, 0), net.tick, sec, count_of(&net, 0) / sec, net.tick / (double) count_of(&net, 0));
	net_free(&net);
}

int main(int argc, char** argv) {
	if (argc < 2) { fprintf(stderr, "usage: %s romdir\n", argv[0]); return 2; }
	g_romdir = argv[1];
	if (argc > 2) { bench(); return 0; }
	instant_reply("multi", "multi", 1);
	instant_reply("n32_master", "n32_slave", 0);
	normal_pair("normal32", 32, 0);
	normal_pair("normal32", 32, 1);
	normal_pair("normal32", 32, 3);
	normal_pair("normal8", 8, 0);
	normal_pair("normal8", 8, 2);
	normal_chain3();
	normal_irq();
	multi(2, 0);
	multi(2, 1);
	multi(3, 2);
	multi(4, 2);
	high_latency();
	mode_mismatch();
	unplug_master("n32_master", 0);
	unplug_master("n8_master", 2);
	unplug_master("multi", 1);
	unplug_child();
	printf("%s\n", g_fail ? "RESULT: FAIL" : "RESULT: PASS");
	return g_fail ? 1 : 0;
}
