/* Game Boy / Game Boy Color harness (manual tool, not part of ctest): boots any .gb/.gbc/.gba ROM, drives scripted input
 * and writes PNG screenshots (2x) so a real cartridge dump can be judged by eye. Never copy the ROM anywhere.
 *
 *   gb_harness rom=game.gbc out=prefix frames=N [shotAt=f1,f2,...] [shot=every] [script="60:S 5:A ..."]
 *              [sav=in.sav] [saveOut=out.sav] [wavOut=out.wav] [stateIn=file] [stateOut=file] [watch=C100,C101] [watchEvery=N]
 *   stateIn / stateOut: raw core states (loaded after the ROM and the save), so a long scripted run (a Pokemon intro) can be continued
 *   in steps. watch prints those work RAM bytes every watchEvery frames (default: at the end only).
 *
 * Script keys: A B E(select) S(start) R(ight) L(eft) U D (GBA-style letters as in emerald_harness: R=right, L=left). */
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

int main(int argc, char** argv) {
	crc_init();
	const char* rom = arg(argc, argv, "rom", NULL);
	const char* out = arg(argc, argv, "out", "shot");
	long frames = atol(arg(argc, argv, "frames", "300"));
	const char* every = arg(argc, argv, "shot", NULL);
	const char* probe = arg(argc, argv, "probe", NULL); /* probe=N: every N frames print how much of the save is not 0xFF */
	const char* shotAt = arg(argc, argv, "shotAt", NULL);
	const char* sav = arg(argc, argv, "sav", NULL);
	const char* saveOut = arg(argc, argv, "saveOut", NULL);
	const char* stateIn = arg(argc, argv, "stateIn", NULL);
	const char* stateOut = arg(argc, argv, "stateOut", NULL);
	const char* watch = arg(argc, argv, "watch", NULL);
	const char* poke = arg(argc, argv, "poke", NULL); /* poke=D158:80,D159:92 : bytes written to the work RAM right after the state is loaded */
	long watchEvery = atol(arg(argc, argv, "watchEvery", "0"));
	if (!rom) { fprintf(stderr, "usage: gb_harness rom=... out=prefix frames=N [shotAt=a,b] [shot=every] [script=...] [sav=..] [saveOut=..]\n"); return 2; }
	struct Script sc;
	parse_script(&sc, arg(argc, argv, "script", NULL));
	gba_core* c = gba_create();
	if (!c || gba_load_rom(c, rom) != 0) { printf("cannot load %s\n", rom); return 2; }
	char title[16], code[8];
	gba_rom_info(c, title, code);
	int W = gba_frame_width(c), H = gba_frame_height(c);
	printf("platform=%d %dx%d title='%s' code='%s' save=%zu\n", gba_platform(c), W, H, title, code, gba_save_size(c));
	if (sav) {
		size_t n;
		uint8_t* b = slurp(sav, &n);
		if (!b || gba_load_save(c, b, n) != 0) { printf("cannot load save\n"); return 2; }
		free(b);
	}
	if (stateIn) {
		size_t n;
		uint8_t* b = slurp(stateIn, &n);
		if (!b || gba_load_state(c, b, n) != 0) { printf("cannot load state %s\n", stateIn); return 2; }
		free(b);
	}
	if (poke) {
		const char* q = poke;
		while (*q) {
			unsigned long a = strtoul(q, (char**) &q, 16);
			if (*q == ':') ++q;
			unsigned long v = strtoul(q, (char**) &q, 16);
			uint32_t w = gba_priv_read32(c, (uint32_t) a);
			gba_priv_write32(c, (uint32_t) a, (w & 0xFFFFFF00u) | (uint32_t) (v & 0xFF)); /* the 3 following bytes are written back unchanged */
			if (*q == ',') ++q; else break;
		}
	}
	uint8_t* big = malloc((size_t) W * 2 * H * 2 * 4);
	long long audio = 0;
	for (long f = 0; f < frames; ++f) {
		gba_set_keys(c, (uint32_t) keys_at(&sc, f));
		if (gba_run_frame(c) != GBA_RUN_FRAME_DONE) { printf("run error at frame %ld\n", f); return 1; }
		int16_t pcm[4096];
		size_t n;
		while ((n = gba_read_audio(c, pcm, 2048)) > 0) audio += (long long) n;
		if (probe && f % atol(probe) == 0 && gba_save_size(c)) { /* count bytes that are not erased (0xFF) in the live save */
			size_t n = gba_save_size(c);
			uint8_t* b = malloc(n);
			n = gba_get_save(c, b, n);
			size_t used = 0;
			for (size_t i = 0; i < n; ++i) used += b[i] != 0xFF;
			printf("frame %ld save bytes in use: %zu\n", f, used);
			free(b);
		}
		if (watch && ((watchEvery && (f + 1) % watchEvery == 0) || f + 1 == frames)) {
			printf("frame %ld watch:", f + 1);
			const char* q = watch;
			while (*q) { unsigned long a = strtoul(q, (char**) &q, 16); printf(" %04lX=%02X", a, gba_priv_read16(c, (uint32_t) a) & 0xFF); if (*q == ',') ++q; else break; }
			printf("\n");
		}
		int take = every != NULL && (f + 1) % atol(every) == 0;
		if (!take && shotAt) {
			const char* p = shotAt;
			while (*p) { if (strtol(p, (char**) &p, 10) == f + 1) take = 1; if (*p == ',') ++p; else break; }
		}
		if (take) {
			const uint8_t* fb = gba_framebuffer(c);
			uint32_t hist = 0;
			for (int y = 0; y < H; ++y)
				for (int x = 0; x < W; ++x) {
					const uint8_t* px = fb + ((size_t) y * W + x) * 4;
					hist += px[0] + px[1] + px[2];
					for (int dy = 0; dy < 2; ++dy)
						for (int dx = 0; dx < 2; ++dx)
							memcpy(big + (((size_t) (y * 2 + dy) * W * 2) + x * 2 + dx) * 4, px, 4);
				}
			char path[1024];
			snprintf(path, sizeof(path), "%s_%05ld.png", out, f + 1);
			write_png(path, big, W * 2, H * 2);
			printf("shot %s (sum %u)\n", path, hist);
		}
	}
	printf("frames=%ld audio=%lld (%.1f per frame) dirty=%d\n", frames, audio, (double) audio / frames, gba_save_dirty(c));
	if (stateOut) {
		size_t n = gba_state_size(c);
		uint8_t* b = malloc(n);
		n = gba_save_state(c, b, n);
		FILE* fo = fopen(stateOut, "wb");
		if (fo) { fwrite(b, 1, n, fo); fclose(fo); printf("state %zu bytes -> %s\n", n, stateOut); }
		free(b);
	}
	if (saveOut) {
		size_t n = gba_save_size(c);
		uint8_t* b = malloc(n ? n : 1);
		n = gba_get_save(c, b, n);
		FILE* fo = fopen(saveOut, "wb");
		if (fo) { fwrite(b, 1, n, fo); fclose(fo); printf("saved %zu bytes\n", n); }
		free(b);
	}
	gba_destroy(c);
	free(big);
	return 0;
}
