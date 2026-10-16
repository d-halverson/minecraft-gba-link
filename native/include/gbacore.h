/* gbacore.h - C ABI of the GBA / Game Boy / Game Boy Color emulator shim (mGBA 0.10.5 + serial-level GBA link cable).
 * Contract: gba/SPEC.md. API version 4. */
#ifndef GBACORE_H
#define GBACORE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(GBACORE_BUILD_SHARED)
#    define GBA_API __declspec(dllexport)
#  elif defined(GBACORE_STATIC)
#    define GBA_API
#  else
#    define GBA_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__) || defined(__clang__)
#  define GBA_API __attribute__((visibility("default")))
#else
#  define GBA_API
#endif

typedef struct gba_core gba_core;

#define GBA_WIDTH  240
#define GBA_HEIGHT 160
#define GB_WIDTH   160         /* Game Boy / Game Boy Color screen (API version 4) */
#define GB_HEIGHT  144
#define GBA_PLATFORM_GBA 0     /* gba_platform() */
#define GBA_PLATFORM_GB  1     /* Game Boy and Game Boy Color */
#define GBA_AUDIO_RATE 32768   /* output sample rate, stereo int16 interleaved */
#define GBA_LINK_MSG_MAX 64    /* max bytes of one opaque link message */

/* run status */
#define GBA_RUN_FRAME_DONE 0   /* a full video frame was produced */
#define GBA_RUN_LINK_WAIT  1   /* stopped mid-frame: waiting for link data; call
                                  gba_link_deliver (or unplug) then gba_run_frame again
                                  to continue the SAME frame */
#define GBA_RUN_ERROR     -1

GBA_API uint32_t    gba_api_version(void);              /* returns 4 for this spec (3 = GBA only, 2 = no groups, 1 = no gba_set_rtc) */
GBA_API gba_core*   gba_create(void);
GBA_API void        gba_destroy(gba_core*);

/* Loads a GBA, Game Boy or Game Boy Color ROM. The system is detected from the ROM contents (mGBA's header checks:
   the Game Boy cartridge logo at 0x104), never from the file name; anything that is not a Game Boy ROM loads as GBA.
   Game Boy ROMs larger than 8 MiB and GBA ROMs larger than 32 MiB fail (-2). Loading a ROM may replace the emulation
   core (and dissolves a lockstep group the core was in); a pending virtual RTC is cleared (call gba_set_rtc again). */
GBA_API int         gba_load_rom(gba_core*, const char* utf8_path);   /* 0 ok, <0 error */
GBA_API void        gba_reset(gba_core*);
/* Fill buf with the ROM's internal title (up to 12 chars) + game code (4 chars) NUL-terminated.
   GBA: header title and game code. Game Boy / Color: the title is up to 11 printable characters at 0x134; the code is
   the manufacturer code at 0x13F..0x142 when those are 4 upper-case letters/digits, else 'G' + 3 hex digits (the
   header checksum byte 0x14D and the low nibble of the global checksum byte 0x14F). */
GBA_API int         gba_rom_info(gba_core*, char* title_out /*>=13*/, char* code_out /*>=5*/);

/* ---- Platform (API version 4) ---- */
GBA_API int         gba_platform(gba_core*);            /* GBA_PLATFORM_GBA (0) or GBA_PLATFORM_GB (1: Game Boy and Color) */
GBA_API int         gba_frame_width(gba_core*);         /* 240 (GBA) or 160 (Game Boy), also before a ROM is loaded: 240 */
GBA_API int         gba_frame_height(gba_core*);        /* 160 (GBA) or 144 (Game Boy) */

/* Keys: GBA KEYINPUT bit order, 1 = pressed.
   A=0 B=1 Select=2 Start=3 Right=4 Left=5 Up=6 Down=7 R=8 L=9
   Game Boy / Color use bits 0-7 for A, B, Select, Start and the D-pad; R and L are ignored. */
GBA_API void        gba_set_keys(gba_core*, uint32_t keymask);

GBA_API int         gba_run_frame(gba_core*);           /* returns GBA_RUN_* */

/* gba_frame_width() x gba_frame_height() pixels (240x160 GBA, 160x144 Game Boy), row-major, each pixel 4 bytes in
   memory order R,G,B,A (A=255). Valid until the next gba_run_frame. */
GBA_API const uint8_t* gba_framebuffer(gba_core*);

/* Drain up to max_frames stereo frames (2 int16 each) produced so far. Returns frames written. */
GBA_API size_t      gba_read_audio(gba_core*, int16_t* out, size_t max_frames);

/* Battery save (SRAM/Flash/EEPROM, plus RTC if present), in mGBA's .sav format.
   Game Boy: the cartridge RAM followed by the 48 byte VBA-compatible RTC footer for MBC3+RTC carts (Pokemon Gold/Silver/
   Crystal). Unlike GBA the size is known right after gba_load_rom (0 for carts without battery RAM). */
GBA_API size_t      gba_save_size(gba_core*);           /* bytes currently in use, 0 = none */
GBA_API size_t      gba_get_save(gba_core*, uint8_t* out, size_t cap);   /* returns bytes written */
GBA_API int         gba_load_save(gba_core*, const uint8_t* data, size_t len); /* after load_rom, before first run; 0 ok */
GBA_API int         gba_save_dirty(gba_core*);          /* 1 if save changed since last gba_get_save */

/* Save states (client-local only). */
GBA_API size_t      gba_state_size(gba_core*);
GBA_API size_t      gba_save_state(gba_core*, uint8_t* out, size_t cap);
GBA_API int         gba_load_state(gba_core*, const uint8_t* data, size_t len);

/* ---- Link cable (GBA only) ----
   A Game Boy core has no link support yet (API 4): gba_link_configure is ignored, gba_link_poll_outgoing returns 0,
   gba_link_deliver is ignored and gba_group_add fails (-1). Check gba_platform() first.
   Messages are OPAQUE to Java and the server: Java only forwards bytes between cores.
   player_index: 0 = parent/master, 1..3 = children. player_count < 2 = cable unplugged.
   Changing configuration while GBA_RUN_LINK_WAIT is pending must unblock the core
   (pending transfer completes as if no partner: 0xFFFF / 0xFFFFFFFF). */
GBA_API void        gba_link_configure(gba_core*, int player_index, int player_count);
/* Pop one outgoing message; returns its length (1..GBA_LINK_MSG_MAX), 0 if none. */
GBA_API size_t      gba_link_poll_outgoing(gba_core*, uint8_t* out, size_t cap);
/* Deliver one message received from another player. */
GBA_API void        gba_link_deliver(gba_core*, int from_player_index, const uint8_t* data, size_t len);

/* ---- Virtual RTC (API version 2) ----
   mode GBA_RTC_WALL (0, default): the cartridge clock follows the machine's wall clock and local time zone.
   mode GBA_RTC_VIRTUAL (1): the clock reads epoch_seconds (UNIX time, UTC) NOW and then advances with *emulated*
   time (one video frame = 280896 cycles at 16777216 Hz); the time zone is ignored (UTC). Needed for input-sync
   netplay, where every machine must see the same clock. Call it after gba_load_rom and BEFORE gba_load_save /
   gba_load_state; loading a state keeps the virtual clock where it was. While any core is in virtual mode the
   process time zone for C library time conversions is forced to UTC (restored when the last one leaves).
   Switching back to GBA_RTC_WALL keeps the in-game clock continuous. Returns 0, <0 on a bad argument. */
#define GBA_RTC_WALL    0
#define GBA_RTC_VIRTUAL 1
GBA_API int         gba_set_rtc(gba_core*, int64_t epoch_seconds, int mode);

/* ---- Lockstep groups (API version 3) ----
   Several cores of ONE process linked with mGBA's own GBASIOLockstep (the code behind mGBA's multiplayer windows) and
   stepped by this library in a fixed order on the calling thread: deterministic, no link messages, no LINK_WAIT.
   Used by input-sync netplay. A core in a group has its classic cable unplugged and cannot be run with gba_run_frame.
   Create the group for n cores (1..4), then gba_group_add the cores in player order (index 0 = master); load the ROM
   (and save / state / virtual RTC) BEFORE adding. gba_group_run_frame advances every core by one video frame (each
   core's framebuffer shows its completed frame; audio is queued per core).
   Save states of grouped cores carry the lockstep state in a trailer (gba_state_size grows by 128 bytes); states with
   or without the trailer can be loaded into grouped or ungrouped cores (a trailer is kept and applied when the last
   core joins; without one the link restarts idle).
   Destroying a member core, loading a ROM into it, or gba_group_destroy dissolves the group: the remaining cores get
   their classic link driver back (idle). */
typedef struct gba_group gba_group;
GBA_API gba_group*  gba_group_create(int players);
GBA_API int         gba_group_add(gba_group*, gba_core*);          /* 0 ok, <0 error (also for a Game Boy core) */
GBA_API int         gba_group_run_frame(gba_group*);                /* GBA_RUN_FRAME_DONE or GBA_RUN_ERROR */
GBA_API void        gba_group_destroy(gba_group*);
/* Diagnostics: how often the scheduler found every core asleep and had to wake them (expected 0). */
GBA_API long        gba_group_stalls(gba_group*);

#ifdef __cplusplus
}
#endif
#endif
