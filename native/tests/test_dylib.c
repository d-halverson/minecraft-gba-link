/* Loads the real shared library the way the Java side will (dlopen / LoadLibrary), checks that only the
 * gba_* ABI is visible and that it works end to end. Does not link against gbacore. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#ifdef _WIN32
#include <windows.h>
#define LOAD(p) LoadLibraryA(p)
#define SYM(h, n) ((void*) GetProcAddress((HMODULE) (h), n))
#else
#include <dlfcn.h>
#define LOAD(p) dlopen(p, RTLD_NOW | RTLD_LOCAL)
#define SYM(h, n) dlsym(h, n)
#endif

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(int argc, char** argv) {
	if (argc < 3) { fprintf(stderr, "usage: %s libgbacore rom.gba\n", argv[0]); return 2; }
	void* h = LOAD(argv[1]);
	CHECK(h != NULL, "cannot load %s", argv[1]);
	if (!h) { return 1; }
	static const char* names[] = {
		"gba_api_version", "gba_create", "gba_destroy", "gba_load_rom", "gba_reset", "gba_rom_info",
		"gba_set_keys", "gba_run_frame", "gba_framebuffer", "gba_read_audio", "gba_save_size",
		"gba_get_save", "gba_load_save", "gba_save_dirty", "gba_state_size", "gba_save_state",
		"gba_load_state", "gba_link_configure", "gba_link_poll_outgoing", "gba_link_deliver", "gba_set_rtc",
		"gba_group_create", "gba_group_add", "gba_group_run_frame", "gba_group_destroy", "gba_group_stalls" };
	for (size_t i = 0; i < sizeof(names) / sizeof(*names); ++i) {
		CHECK(SYM(h, names[i]) != NULL, "missing export %s", names[i]);
	}
	CHECK(SYM(h, "mCoreCreate") == NULL, "mGBA symbols must not be exported");
	CHECK(SYM(h, "GBACoreCreate") == NULL, "mGBA symbols must not be exported");
	CHECK(SYM(h, "gba_priv_read32") == NULL, "test hooks must not be exported");

	uint32_t (*api)(void) = (uint32_t (*)(void)) SYM(h, "gba_api_version");
	void* (*create)(void) = (void* (*)(void)) SYM(h, "gba_create");
	void (*destroy)(void*) = (void (*)(void*)) SYM(h, "gba_destroy");
	int (*load)(void*, const char*) = (int (*)(void*, const char*)) SYM(h, "gba_load_rom");
	int (*run)(void*) = (int (*)(void*)) SYM(h, "gba_run_frame");
	const uint8_t* (*fbf)(void*) = (const uint8_t* (*)(void*)) SYM(h, "gba_framebuffer");
	size_t (*audio)(void*, int16_t*, size_t) = (size_t (*)(void*, int16_t*, size_t)) SYM(h, "gba_read_audio");
	CHECK(api() == 3, "api version %u", api());
	void* c = create();
	CHECK(c != NULL, "create");
	CHECK(load(c, argv[2]) == 0, "load rom");
	size_t frames = 0;
	static int16_t buf[4096 * 2];
	for (int i = 0; i < 60; ++i) {
		CHECK(run(c) == 0, "run_frame");
		frames += audio(c, buf, 4096);
	}
	const uint8_t* fb = fbf(c);
	int white = 0, black = 0;
	for (int i = 0; i < 240 * 160; ++i) {
		if (fb[i * 4] == 255 && fb[i * 4 + 1] == 255 && fb[i * 4 + 2] == 255) { ++white; }
		if (fb[i * 4] == 0 && fb[i * 4 + 1] == 0 && fb[i * 4 + 2] == 0) { ++black; }
		if (fb[i * 4 + 3] != 255) { CHECK(0, "alpha"); break; }
	}
	printf("  60 frames: %zu audio frames, %d white / %d black pixels\n", frames, white, black);
	CHECK(white > 0 && black > 0, "frame not drawn");
	CHECK(frames > 60 * 540, "audio frames %zu", frames);
	destroy(c);
	printf("%s\n", g_fail ? "RESULT: FAIL" : "RESULT: PASS");
	return g_fail ? 1 : 0;
}
