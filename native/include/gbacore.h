/* gbacore.h - C ABI of the GBA emulator shim (mGBA 0.10.5 + serial-level link cable).
 * Contract: gba/SPEC.md. API version 1. */
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
#define GBA_AUDIO_RATE 32768   /* output sample rate, stereo int16 interleaved */
#define GBA_LINK_MSG_MAX 64    /* max bytes of one opaque link message */

/* run status */
#define GBA_RUN_FRAME_DONE 0   /* a full video frame was produced */
#define GBA_RUN_LINK_WAIT  1   /* stopped mid-frame: waiting for link data; call
                                  gba_link_deliver (or unplug) then gba_run_frame again
                                  to continue the SAME frame */
#define GBA_RUN_ERROR     -1

GBA_API uint32_t    gba_api_version(void);              /* returns 1 for this spec */
GBA_API gba_core*   gba_create(void);
GBA_API void        gba_destroy(gba_core*);

GBA_API int         gba_load_rom(gba_core*, const char* utf8_path);   /* 0 ok, <0 error */
GBA_API void        gba_reset(gba_core*);
/* Fill buf with the ROM's internal title (12 chars) + game code (4 chars) NUL-terminated. */
GBA_API int         gba_rom_info(gba_core*, char* title_out /*>=13*/, char* code_out /*>=5*/);

/* Keys: GBA KEYINPUT bit order, 1 = pressed.
   A=0 B=1 Select=2 Start=3 Right=4 Left=5 Up=6 Down=7 R=8 L=9 */
GBA_API void        gba_set_keys(gba_core*, uint32_t keymask);

GBA_API int         gba_run_frame(gba_core*);           /* returns GBA_RUN_* */

/* 240*160 pixels, row-major, each pixel 4 bytes in memory order R,G,B,A (A=255).
   Valid until the next gba_run_frame. */
GBA_API const uint8_t* gba_framebuffer(gba_core*);

/* Drain up to max_frames stereo frames (2 int16 each) produced so far. Returns frames written. */
GBA_API size_t      gba_read_audio(gba_core*, int16_t* out, size_t max_frames);

/* Battery save (SRAM/Flash/EEPROM, plus RTC if present), in mGBA's .sav format. */
GBA_API size_t      gba_save_size(gba_core*);           /* bytes currently in use, 0 = none */
GBA_API size_t      gba_get_save(gba_core*, uint8_t* out, size_t cap);   /* returns bytes written */
GBA_API int         gba_load_save(gba_core*, const uint8_t* data, size_t len); /* after load_rom, before first run; 0 ok */
GBA_API int         gba_save_dirty(gba_core*);          /* 1 if save changed since last gba_get_save */

/* Save states (client-local only). */
GBA_API size_t      gba_state_size(gba_core*);
GBA_API size_t      gba_save_state(gba_core*, uint8_t* out, size_t cap);
GBA_API int         gba_load_state(gba_core*, const uint8_t* data, size_t len);

/* ---- Link cable ----
   Messages are OPAQUE to Java and the server: Java only forwards bytes between cores.
   player_index: 0 = parent/master, 1..3 = children. player_count < 2 = cable unplugged.
   Changing configuration while GBA_RUN_LINK_WAIT is pending must unblock the core
   (pending transfer completes as if no partner: 0xFFFF / 0xFFFFFFFF). */
GBA_API void        gba_link_configure(gba_core*, int player_index, int player_count);
/* Pop one outgoing message; returns its length (1..GBA_LINK_MSG_MAX), 0 if none. */
GBA_API size_t      gba_link_poll_outgoing(gba_core*, uint8_t* out, size_t cap);
/* Deliver one message received from another player. */
GBA_API void        gba_link_deliver(gba_core*, int from_player_index, const uint8_t* data, size_t len);

#ifdef __cplusplus
}
#endif
#endif
