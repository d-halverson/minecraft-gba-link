/* Virtual RTC (gba_set_rtc): the clock reads the epoch, advances with emulated frames, is independent of the process
 * time zone, survives loading a state, and the time zone is restored when virtual mode ends. */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void set_tz(const char* tz) {
#ifdef _WIN32
	_putenv_s("TZ", tz ? tz : "");
	_tzset();
#else
	if (tz) setenv("TZ", tz, 1); else unsetenv("TZ");
	tzset();
#endif
}

/* hour of day that localtime() gives for t */
static int local_hour(time_t t) {
	struct tm d;
#ifdef _WIN32
	localtime_s(&d, &t);
#else
	localtime_r(&t, &d);
#endif
	return d.tm_hour;
}

static gba_core* make(const char* rom) {
	gba_core* c = gba_create();
	if (!c || gba_load_rom(c, rom) != 0) {
		printf("  cannot load %s\n", rom);
		exit(2);
	}
	return c;
}

int main(int argc, char** argv) {
	if (argc < 2) return 2;
	const int64_t epoch = 1760000000; /* 2025-10-09 08:53:20 UTC */
	printf("api version %u\n", gba_api_version());
	CHECK(gba_api_version() == 2, "api version");

	/* wall mode by default: tracks time() */
	gba_core* a = make(argv[1]);
	int64_t w = gba_priv_rtc_unix(a);
	CHECK(w >= (int64_t) time(NULL) - 2 && w <= (int64_t) time(NULL) + 2, "wall clock %lld", (long long) w);

	/* virtual mode reads the epoch, advances with frames */
	set_tz("America/New_York");
	CHECK(gba_set_rtc(a, epoch, GBA_RTC_VIRTUAL) == 0, "set_rtc");
	CHECK(gba_priv_rtc_unix(a) == epoch, "virtual start %lld", (long long) gba_priv_rtc_unix(a));
	/* the process zone is UTC while virtual: 08:53 UTC, not 04:53 New York */
	CHECK(local_hour((time_t) epoch) == 8, "time zone forced to UTC (hour %d)", local_hour((time_t) epoch));
	for (int i = 0; i < 600; ++i) gba_run_frame(a); /* 600 frames = 10.04 s */
	int64_t t1 = gba_priv_rtc_unix(a);
	CHECK(t1 == epoch + 10, "after 600 frames: +%lld s (want +10)", (long long) (t1 - epoch));
	for (int i = 0; i < 6000; ++i) gba_run_frame(a);
	CHECK(gba_priv_rtc_unix(a) == epoch + (6600LL * 280896) / 16777216, "after 6600 frames: +%lld s", (long long) (gba_priv_rtc_unix(a) - epoch));

	/* a second core at a different frame count: loading A's state keeps ITS clock where it was, and both advance equally */
	gba_core* b = make(argv[1]);
	CHECK(gba_set_rtc(b, epoch, GBA_RTC_VIRTUAL) == 0, "set_rtc b");
	size_t n = gba_state_size(a);
	uint8_t* st = malloc(n);
	CHECK(gba_save_state(a, st, n) == n, "save state");
	CHECK(gba_load_state(b, st, n) == 0, "load state");
	CHECK(gba_priv_rtc_unix(b) == epoch, "clock kept across load_state (%lld)", (long long) (gba_priv_rtc_unix(b) - epoch));
	for (int i = 0; i < 600; ++i) gba_run_frame(b);
	CHECK(gba_priv_rtc_unix(b) == epoch + 10, "b advances with its frames");

	/* bad mode */
	CHECK(gba_set_rtc(a, 0, 7) < 0, "bad mode rejected");

	/* back to wall mode: wall clock again, time zone restored */
	gba_set_rtc(a, 0, GBA_RTC_WALL);
	CHECK(local_hour((time_t) epoch) == 8, "still virtual because b is virtual (hour %d)", local_hour((time_t) epoch));
	gba_destroy(b);
	CHECK(local_hour((time_t) epoch) == 4, "time zone restored to New York (hour %d)", local_hour((time_t) epoch));
	w = gba_priv_rtc_unix(a);
	CHECK(w >= (int64_t) time(NULL) - 2 && w <= (int64_t) time(NULL) + 2, "wall clock again %lld", (long long) w);
	gba_destroy(a);
	free(st);
	printf(g_fail ? "FAILED (%d)\n" : "OK\n", g_fail);
	return g_fail ? 1 : 0;
}
