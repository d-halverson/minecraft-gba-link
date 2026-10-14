/* Real-game link harness (manual tool, not part of ctest: needs a commercial ROM that is never committed).
 *
 * Boots two cores from saves, drives scripted inputs, and writes side-by-side screenshots (PNG, both consoles at 2x) so
 * the Cable Club link can be judged by eye. The link is either the custom driver behind the zero-latency fixed-order
 * router (what NetplaySim did up to 0.4.1) or mGBA's own lockstep (gba_group_*, 0.4.2).
 *
 *   emerald_harness mode=custom|lockstep rom=... savA=... savB=... out=prefix frames=N shot=every
 *                   [scriptA="60:S 5:A ..."] [scriptB="..."] [shotAt=f1,f2,...] [state=in.state] [saveState=out.state]
 *
 * A script is a list of "frames:keys" steps; keys are letters A B E(select) S(start) R L U D (right/left/up/down).
 * Example "300: 5:S 20: 5:A" = idle 300 frames, tap Start, idle 20, tap A. The last step's keys stay if the script ends.
 * Prints a line per screenshot plus the final link counters. Never copy the ROM or saves anywhere. */
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
static int keys_at(const struct Script* sc, long f) {
	long t = 0;
	for (int i = 0; i < sc->n; ++i) {
		t += sc->s[i].frames;
		if (f < t) return sc->s[i].keys;
	}
	return 0;
}

/* ---------------- custom-driver router (same algorithm as NetplaySim up to 0.4.1) ---------------- */
#define N 2
#define MAXQ 8192
struct Msg { int from; uint8_t d[GBA_LINK_MSG_MAX]; size_t n; };
static struct Msg inbox[N][MAXQ];
static int qn[N];
static gba_core* c[N];
static long long_msgs;

static void pump(int i) {
	uint8_t m[GBA_LINK_MSG_MAX];
	size_t n;
	while ((n = gba_link_poll_outgoing(c[i], m, sizeof(m))) > 0) {
		++long_msgs;
		for (int j = 0; j < N; ++j) {
			if (j == i) continue;
			struct Msg* q = &inbox[j][qn[j]++];
			q->from = i; q->n = n; memcpy(q->d, m, n);
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
static void tick_custom(void) {
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

static void shot(const char* prefix, long frame) {
	static uint8_t big[480 * 2 * 160 * 2 * 4];
	int W = 960, H = 320;
	for (int p = 0; p < 2; ++p) {
		const uint8_t* fb = gba_framebuffer(c[p]);
		for (int y = 0; y < 160; ++y)
			for (int x = 0; x < 240; ++x)
				for (int dy = 0; dy < 2; ++dy)
					for (int dx = 0; dx < 2; ++dx)
						memcpy(big + (((size_t) (y * 2 + dy) * W) + p * 480 + x * 2 + dx) * 4, fb + ((size_t) y * 240 + x) * 4, 4);
	}
	char path[1024];
	snprintf(path, sizeof(path), "%s_%05ld.png", prefix, frame);
	write_png(path, big, W, H);
	printf("shot %s\n", path);
}

int main(int argc, char** argv) {
	crc_init();
	const char* mode = arg(argc, argv, "mode", "lockstep");
	const char* rom = arg(argc, argv, "rom", NULL);
	const char* sav[2] = { arg(argc, argv, "savA", NULL), arg(argc, argv, "savB", NULL) };
	const char* out = arg(argc, argv, "out", "emerald");
	long frames = atol(arg(argc, argv, "frames", "1800"));
	long every = atol(arg(argc, argv, "shot", "300"));
	struct Script sc[2];
	parse_script(&sc[0], arg(argc, argv, "scriptA", ""));
	parse_script(&sc[1], arg(argc, argv, "scriptB", arg(argc, argv, "scriptA", "")));
	if (!rom) { fprintf(stderr, "usage: see source\n"); return 2; }
	int lockstep = !strcmp(mode, "lockstep");
	const char* stIn[2] = { arg(argc, argv, "stateA", NULL), arg(argc, argv, "stateB", NULL) };
	const char* stOut = arg(argc, argv, "saveState", NULL);
	const char* shotAt = arg(argc, argv, "shotAt", "");

	for (int i = 0; i < N; ++i) {
		c[i] = gba_create();
		if (!c[i] || gba_load_rom(c[i], rom) != 0) { printf("cannot load rom\n"); return 2; }
		gba_set_rtc(c[i], 1760000000, GBA_RTC_VIRTUAL);
		if (sav[i]) {
			size_t n;
			uint8_t* b = slurp(sav[i], &n);
			if (!b || gba_load_save(c[i], b, n) != 0) { printf("cannot load save %s\n", sav[i]); return 2; }
			free(b);
		}
		if (stIn[i]) {
			size_t n;
			uint8_t* b = slurp(stIn[i], &n);
			if (!b || gba_load_state(c[i], b, n) != 0) { printf("cannot load state %s\n", stIn[i]); return 2; }
			free(b);
		}
	}
	gba_group* g = NULL;
	if (lockstep) {
		g = gba_group_create(N);
		for (int i = 0; i < N; ++i) if (gba_group_add(g, c[i]) != 0) { printf("group add failed\n"); return 2; }
	} else {
		for (int i = 0; i < N; ++i) gba_link_configure(c[i], i, N);
		pump(0);
		pump(1);
	}
	printf("mode=%s frames=%ld\n", mode, frames);
	for (long f = 0; f < frames; ++f) {
		for (int i = 0; i < N; ++i) gba_set_keys(c[i], keys_at(&sc[i], f));
		if (lockstep) {
			if (gba_group_run_frame(g) != GBA_RUN_FRAME_DONE) { printf("group error at %ld\n", f); return 1; }
			int16_t pcm[4096];
			for (int i = 0; i < N; ++i) while (gba_read_audio(c[i], pcm, 2048) > 0) { }
		} else {
			tick_custom();
		}
		int take = every > 0 && (f + 1) % every == 0;
		for (const char* p = shotAt; *p;) {
			long v = strtol(p, (char**) &p, 10);
			if (v == f + 1) take = 1;
			if (*p == ',') ++p; else if (*p) break;
		}
		if (take) shot(out, f + 1);
	}
	if (stOut) {
		for (int i = 0; i < N; ++i) {
			size_t n = gba_state_size(c[i]);
			uint8_t* b = malloc(n);
			gba_save_state(c[i], b, n);
			char p[1024];
			snprintf(p, sizeof(p), "%s%c.state", stOut, 'A' + i);
			FILE* f = fopen(p, "wb");
			fwrite(b, 1, n, f);
			fclose(f);
			free(b);
		}
	}
	if (lockstep) printf("lockstep stalls: %ld\n", gba_group_stalls(g));
	else printf("link messages relayed: %ld\n", long_msgs);
	if (g) gba_group_destroy(g);
	for (int i = 0; i < N; ++i) gba_destroy(c[i]);
	return 0;
}
