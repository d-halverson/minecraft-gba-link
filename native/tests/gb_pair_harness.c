/* Two linked Game Boy consoles in one process (manual tool, not part of ctest): the real-cartridge link harness.
 *
 *   gb_pair_harness romA=a.gb romB=b.gb [savA=..] [savB=..] mode=group|classic [latency=N] frames=N out=prefix
 *                   [scriptA="..."] [scriptB="..."] [shotAt=f1,f2] [watch=D35E,D361] [watchEvery=N] [savOutA=..] [savOutB=..]
 *                   [stateInA=..] [stateInB=..] [stateOutA=..] [stateOutB=..]
 *
 *   mode=group:   mGBA's GBSIOLockstep (what same-ROM netplay runs). mode=classic: the byte link driver with a message relay of
 *   `latency` ticks (one tick = one frame of both consoles) between the two cores, i.e. what different ROMs use.
 *   Script keys are those of gb_harness (A B E S R L U D). Never copy the ROMs anywhere. */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------------- tiny PNG writer (stored deflate) ---------------- */
static uint32_t crct[256];
static void crc_init(void) {
	for (uint32_t i = 0; i < 256; ++i) {
		uint32_t c = i;
		for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		crct[i] = c;
	}
}
static uint32_t crc_up(uint32_t crc, const uint8_t* p, size_t n) {
	crc = ~crc;
	while (n--) crc = crct[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
	return ~crc;
}
static void be32(uint8_t* p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; }
static void chunk(FILE* f, const char* type, const uint8_t* d, size_t n) {
	uint8_t h[8];
	be32(h, (uint32_t) n);
	memcpy(h + 4, type, 4);
	fwrite(h, 1, 8, f);
	if (n) fwrite(d, 1, n, f);
	uint32_t c = crc_up(0, (const uint8_t*) type, 4);
	c = crc_up(c, d, n);
	uint8_t t[4];
	be32(t, c);
	fwrite(t, 1, 4, f);
}
static void write_png(const char* path, const uint8_t* rgba, int w, int h) {
	size_t rowb = (size_t) w * 3 + 1, raw = rowb * h;
	uint8_t* img = malloc(raw);
	for (int y = 0; y < h; ++y) {
		img[y * rowb] = 0;
		for (int x = 0; x < w; ++x) memcpy(img + y * rowb + 1 + x * 3, rgba + ((size_t) y * w + x) * 4, 3);
	}
	size_t nblk = (raw + 65534) / 65535, zlen = 2 + raw + nblk * 5 + 4;
	uint8_t* z = malloc(zlen);
	size_t o = 0;
	z[o++] = 0x78; z[o++] = 0x01;
	uint32_t a = 1, b = 0;
	for (size_t i = 0; i < raw; ++i) { a = (a + img[i]) % 65521; b = (b + a) % 65521; }
	for (size_t off = 0; off < raw; off += 65535) {
		size_t n = raw - off > 65535 ? 65535 : raw - off;
		z[o++] = (off + n >= raw) ? 1 : 0;
		z[o++] = n & 0xFF; z[o++] = n >> 8; z[o++] = ~n & 0xFF; z[o++] = (~n >> 8) & 0xFF;
		memcpy(z + o, img + off, n);
		o += n;
	}
	be32(z + o, (b << 16) | a);
	o += 4;
	FILE* f = fopen(path, "wb");
	if (!f) { fprintf(stderr, "cannot write %s\n", path); exit(2); }
	static const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
	fwrite(sig, 1, 8, f);
	uint8_t ihdr[13];
	be32(ihdr, w); be32(ihdr + 4, h);
	ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = ihdr[11] = ihdr[12] = 0;
	chunk(f, "IHDR", ihdr, 13);
	chunk(f, "IDAT", z, o);
	chunk(f, "IEND", NULL, 0);
	fclose(f);
	free(img);
	free(z);
}

/* ---------------- scripts ---------------- */
struct Step { int frames; int keys; };
struct Script { struct Step s[512]; int n; };
static void parse_script(struct Script* sc, const char* txt) {
	sc->n = 0;
	if (!txt) return;
	const char* p = txt;
	while (*p) {
		while (*p == ' ') ++p;
		if (!*p) break;
		int frames = (int) strtol(p, (char**) &p, 10);
		if (*p == ':') ++p;
		int keys = 0;
		while (*p && *p != ' ') {
			switch (*p) {
			case 'A': keys |= 1; break;
			case 'B': keys |= 2; break;
			case 'E': keys |= 4; break;
			case 'S': keys |= 8; break;
			case 'R': keys |= 16; break;
			case 'L': keys |= 32; break;
			case 'U': keys |= 64; break;
			case 'D': keys |= 128; break;
			}
			++p;
		}
		if (sc->n < 512) { sc->s[sc->n].frames = frames; sc->s[sc->n].keys = keys; ++sc->n; }
	}
}

static uint8_t* slurp(const char* path, size_t* n) {
	FILE* f = fopen(path, "rb");
	if (!f) return NULL;
	fseek(f, 0, SEEK_END);
	*n = (size_t) ftell(f);
	fseek(f, 0, SEEK_SET);
	uint8_t* b = malloc(*n);
	if (fread(b, 1, *n, f) != *n) { fclose(f); free(b); return NULL; }
	fclose(f);
	return b;
}

static const char* arg(int argc, char** argv, const char* key, const char* def) {
	size_t kl = strlen(key);
	for (int i = 1; i < argc; ++i) if (!strncmp(argv[i], key, kl) && argv[i][kl] == '=') return argv[i] + kl + 1;
	return def;
}



static int keys_at(const struct Script* sc, long frame) {
	long t = 0;
	for (int i = 0; i < sc->n; ++i) {
		t += sc->s[i].frames;
		if (frame < t) return sc->s[i].keys;
	}
	return sc->n ? sc->s[sc->n - 1].keys : 0;
}


struct Msg { int to, from, due; uint8_t d[GBA_LINK_MSG_MAX]; size_t n; };
#define MAXQ 4096
static struct Msg g_q[MAXQ];
static int g_qn;

static void collect(gba_core* c[2], int i, int tick, int latency) {
	uint8_t buf[GBA_LINK_MSG_MAX];
	size_t len;
	while ((len = gba_link_poll_outgoing(c[i], buf, sizeof(buf))) > 0 && g_qn < MAXQ) {
		struct Msg* m = &g_q[g_qn++];
		m->from = i; m->to = 1 - i; m->n = len; memcpy(m->d, buf, len); m->due = tick + latency;
	}
}

static void deliver_due(gba_core* c[2], int tick, int latency) {
	int w = 0;
	for (int r = 0; r < g_qn; ++r) {
		struct Msg* m = &g_q[r];
		if (m->due <= tick) {
			gba_link_deliver(c[m->to], m->from, m->d, m->n);
			collect(c, m->to, tick, latency);
		} else {
			g_q[w++] = *m;
		}
	}
	g_qn = w;
}

static void shot(const char* out, const char* tag, long f, gba_core* c, uint8_t* big) {
	int W = gba_frame_width(c), H = gba_frame_height(c);
	const uint8_t* fb = gba_framebuffer(c);
	for (int y = 0; y < H; ++y)
		for (int x = 0; x < W; ++x) {
			const uint8_t* px = fb + ((size_t) y * W + x) * 4;
			for (int dy = 0; dy < 2; ++dy)
				for (int dx = 0; dx < 2; ++dx)
					memcpy(big + (((size_t) (y * 2 + dy) * W * 2) + x * 2 + dx) * 4, px, 4);
		}
	char path[1024];
	snprintf(path, sizeof(path), "%s%s_%05ld.png", out, tag, f);
	write_png(path, big, W * 2, H * 2);
}

static gba_core* open_core(const char* rom, const char* sav, const char* st) {
	gba_core* c = gba_create();
	if (!c || gba_load_rom(c, rom) != 0) { printf("cannot load %s\n", rom); exit(2); }
	gba_set_rtc(c, 1760000000, GBA_RTC_VIRTUAL);
	if (sav) {
		size_t n;
		uint8_t* b = slurp(sav, &n);
		if (!b || gba_load_save(c, b, n) != 0) { printf("cannot load save %s\n", sav); exit(2); }
		free(b);
	}
	if (st) {
		size_t n;
		uint8_t* b = slurp(st, &n);
		if (!b || gba_load_state(c, b, n) != 0) { printf("cannot load state %s\n", st); exit(2); }
		free(b);
	}
	return c;
}

static void dump(gba_core* c, const char* path, int state) {
	if (!path) return;
	size_t n = state ? gba_state_size(c) : gba_save_size(c);
	uint8_t* b = malloc(n ? n : 1);
	n = state ? gba_save_state(c, b, n) : gba_get_save(c, b, n);
	FILE* f = fopen(path, "wb");
	if (f) { fwrite(b, 1, n, f); fclose(f); }
	free(b);
}

int main(int argc, char** argv) {
	setvbuf(stdout, NULL, _IOLBF, 0);
	crc_init();
	if (getenv("ROUND_LIMIT")) gba_priv_set_round_limit(atol(getenv("ROUND_LIMIT")));
	const char* romA = arg(argc, argv, "romA", NULL);
	const char* romB = arg(argc, argv, "romB", romA);
	const char* mode = arg(argc, argv, "mode", "group");
	int latency = atoi(arg(argc, argv, "latency", "0"));
	long frames = atol(arg(argc, argv, "frames", "300"));
	const char* out = arg(argc, argv, "out", "pair");
	const char* shotAt = arg(argc, argv, "shotAt", NULL);
	const char* watch = arg(argc, argv, "watch", NULL);
	long watchEvery = atol(arg(argc, argv, "watchEvery", "0"));
	if (!romA) { fprintf(stderr, "usage: gb_pair_harness romA=.. romB=.. [savA=..] [savB=..] mode=group|classic ...\n"); return 2; }
	struct Script sc[2];
	parse_script(&sc[0], arg(argc, argv, "scriptA", NULL));
	parse_script(&sc[1], arg(argc, argv, "scriptB", NULL));
	gba_core* c[2];
	c[0] = open_core(romA, arg(argc, argv, "savA", NULL), arg(argc, argv, "stateInA", NULL));
	c[1] = open_core(romB, arg(argc, argv, "savB", NULL), arg(argc, argv, "stateInB", NULL));
	int group = strcmp(mode, "classic") != 0;
	gba_group* g = NULL;
	if (group) {
		g = gba_group_create(2);
		for (int i = 0; i < 2; ++i) if (gba_group_add(g, c[i]) != 0) { printf("group add failed\n"); return 1; }
	} else {
		gba_link_configure(c[0], 0, 2);
		gba_link_configure(c[1], 1, 2);
	}
	int W = gba_frame_width(c[0]), H = gba_frame_height(c[0]);
	uint8_t* big = malloc((size_t) W * 2 * H * 2 * 4);
	long waits = 0, maxRounds = 0, slow = 0;
	for (long f = 0; f < frames; ++f) {
		for (int i = 0; i < 2; ++i) gba_set_keys(c[i], (uint32_t) keys_at(&sc[i], f));
		if (group) {
			if (gba_group_run_frame(g) != GBA_RUN_FRAME_DONE) { printf("group run error at frame %ld\n", f); return 1; }
			long r = gba_priv_group_rounds(g);
			if (r > maxRounds) maxRounds = r;
			if (r > 5000 && slow++ < 20) printf("frame %ld needed %ld scheduler rounds\n", f, r);
		} else {
			deliver_due(c, (int) f, latency);
			for (int i = 0; i < 2; ++i) {
				int r;
				int guard = 0;
				while ((r = gba_run_frame(c[i])) == GBA_RUN_LINK_WAIT && guard++ < 100000) {
					++waits;
					collect(c, i, (int) f, latency);
					/* the master is blocked: time for the network passes only when nothing else can happen, so let the other side run on */
					if (latency == 0) deliver_due(c, (int) f, latency);
					else break;
				}
				collect(c, i, (int) f, latency);
				if (r == GBA_RUN_ERROR) { printf("run error at frame %ld\n", f); return 1; }
			}
		}
		for (int i = 0; i < 2; ++i) { int16_t pcm[4096]; while (gba_read_audio(c[i], pcm, 2048) > 0) { } }
		if (watch && ((watchEvery && (f + 1) % watchEvery == 0) || f + 1 == frames)) {
			for (int i = 0; i < 2; ++i) {
				printf("frame %ld %c:", f + 1, 'A' + i);
				const char* q = watch;
				while (*q) { unsigned long a = strtoul(q, (char**) &q, 16); printf(" %04lX=%02X", a, gba_priv_read16(c[i], (uint32_t) a) & 0xFF); if (*q == ',') ++q; else break; }
				printf("\n");
			}
		}
		int take = 0;
		if (shotAt) {
			const char* p = shotAt;
			while (*p) { if (strtol(p, (char**) &p, 10) == f + 1) take = 1; if (*p == ',') ++p; else break; }
		}
		if (take) { shot(out, "A", f + 1, c[0], big); shot(out, "B", f + 1, c[1], big); }
	}
	printf("frames=%ld mode=%s link waits=%ld max scheduler rounds per frame %ld\n", frames, mode, waits, maxRounds);
	dump(c[0], arg(argc, argv, "savOutA", NULL), 0);
	dump(c[1], arg(argc, argv, "savOutB", NULL), 0);
	dump(c[0], arg(argc, argv, "stateOutA", NULL), 1);
	dump(c[1], arg(argc, argv, "stateOutB", NULL), 1);
	if (g) gba_group_destroy(g);
	for (int i = 0; i < 2; ++i) gba_destroy(c[i]);
	free(big);
	return 0;
}
