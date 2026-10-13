# GBA Link mod - design spec (contract between the native core and the mod)

Any change to the C ABI below must be made on both sides (native shim and the mod's Java binding).

## Layout

```
gba/
  SPEC.md            this file
  native/            C shim + mGBA (third_party/mgba, git submodule), CMake, tests
  dist/natives/<os>-<arch>/   built shared libs (written by native/build_*.sh, packed into the jar by Gradle)
                     os-arch names: macos-x86_64, macos-aarch64, windows-x86_64, linux-x86_64
                     lib names: libgbacore.dylib / gbacore.dll / libgbacore.so
  mod/               Fabric mod "gbalink" (client + server, one jar)
```

Toolchain: JDK 25, Minecraft 26.3, Fabric loader 0.19.5, Fabric API 0.162.0+26.3,
Fabric Loom 1.18, CMake 3.16+, a C compiler (Apple clang on macOS, Zig 0.17 for the Windows and Linux
builds). See the top-level README for build instructions.

## C ABI — `gba/native/include/gbacore.h`

All functions are `extern "C"`, exported, C-only types. Opaque handle. Single-threaded per
core: Java calls every function for a given core from one emulator thread.

```c
#include <stdint.h>
#include <stddef.h>

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

uint32_t    gba_api_version(void);              /* returns 1 for this spec */
gba_core*   gba_create(void);
void        gba_destroy(gba_core*);

int         gba_load_rom(gba_core*, const char* utf8_path);   /* 0 ok, <0 error */
void        gba_reset(gba_core*);
/* Fill buf with the ROM's internal title (12 chars) + game code (4 chars) NUL-terminated. */
int         gba_rom_info(gba_core*, char* title_out /*>=13*/, char* code_out /*>=5*/);

/* Keys: GBA KEYINPUT bit order, 1 = pressed.
   A=0 B=1 Select=2 Start=3 Right=4 Left=5 Up=6 Down=7 R=8 L=9 */
void        gba_set_keys(gba_core*, uint32_t keymask);

int         gba_run_frame(gba_core*);           /* returns GBA_RUN_* */

/* 240*160 pixels, row-major, each pixel 4 bytes in memory order R,G,B,A (A=255).
   Valid until the next gba_run_frame. */
const uint8_t* gba_framebuffer(gba_core*);

/* Drain up to max_frames stereo frames (2 int16 each) produced so far. Returns frames written. */
size_t      gba_read_audio(gba_core*, int16_t* out, size_t max_frames);

/* Battery save (SRAM/Flash/EEPROM, plus RTC if present), in mGBA's .sav format. */
size_t      gba_save_size(gba_core*);           /* bytes currently in use, 0 = none */
size_t      gba_get_save(gba_core*, uint8_t* out, size_t cap);   /* returns bytes written */
int         gba_load_save(gba_core*, const uint8_t* data, size_t len); /* call after load_rom, before first run; 0 ok */
int         gba_save_dirty(gba_core*);          /* 1 if save changed since last gba_get_save */

/* Save states (client-local only). */
size_t      gba_state_size(gba_core*);
size_t      gba_save_state(gba_core*, uint8_t* out, size_t cap);
int         gba_load_state(gba_core*, const uint8_t* data, size_t len);

/* ---- Link cable ----
   Messages are OPAQUE to Java and the server: Java only forwards bytes between cores.
   player_index: 0 = parent/master, 1..3 = children. player_count < 2 = cable unplugged.
   Changing configuration while GBA_RUN_LINK_WAIT is pending must unblock the core
   (pending transfer completes as if no partner: 0xFFFF / 0xFFFFFFFF). */
void        gba_link_configure(gba_core*, int player_index, int player_count);
/* Pop one outgoing message; returns its length (1..GBA_LINK_MSG_MAX), 0 if none. */
size_t      gba_link_poll_outgoing(gba_core*, uint8_t* out, size_t cap);
/* Deliver one message received from another player. */
void        gba_link_deliver(gba_core*, int from_player_index, const uint8_t* data, size_t len);
```

Link semantics (implemented in the shim, invisible to Java): lockstep at the serial-transfer
level. The master core, on starting a transfer (Normal 8/32-bit or Multiplayer 16-bit),
emits a message and returns GBA_RUN_LINK_WAIT until all children's replies arrive. Child
cores run freely; when a master transfer message is delivered they complete their transfer
(data in, IRQ if enabled) and emit a reply containing their outgoing SIO data. Every message
the shim emits is broadcast by the server to all OTHER players in the room; cores ignore
messages not meant for them.

## Java ↔ native

The mod binds the ABI with the Java 25 Foreign Function & Memory API (no JNI). The client
extracts `natives/<os>-<arch>/<lib>` from the jar to `<gameDir>/gbalink/natives/` and loads
it. If no native exists for the platform the emulator UI says so instead of crashing.

## Network protocol (Fabric custom payloads, namespace `gbalink`)

Only sent to clients where `ServerPlayNetworking.canSend` is true. Vanilla / non-gbalink
clients must be able to join and see the items (no new synced registry entries, no custom
command argument types).

Protocol number: **2** (0.2.0). Hello/Welcome carry it; mismatches never crash: the server refuses to `open`
for a client with a different protocol ("update the mod"), and a client treats a server that sends no `welcome`
(protocol 1) as unable to do revisions/imports and shows "update the mod". New fields are appended last and
decoded tolerantly.

- C2S `hello` {protocol} — client announces the mod and its protocol.
- S2C `welcome` {protocol} — server's protocol (new in 2; only sent if the client can receive it).
- S2C `open` {pakId UUID, romSha1 hex, title, save bytes, revision} — open emulator for this pak.
  `revision` (new in 2, appended; "" if absent) = lowercase hex SHA-256 of `save` (a missing save is the hash
  of zero bytes). The server only sends `open` to protocol-2 clients.
- C2S `save_chunk` {pakId, offset, total, bytes ≤ 30000} — client uploads save
  (serverbound payloads are limited to 32 KiB). Server assembles and writes atomically (at most ~5 saves/s).
- S2C `save_ack` {pakId, revision, accepted, message} — (new in 2) sent after every completed upload or import.
  `revision` is the SHA-256 of what the server now stores (unchanged if `accepted` is false). The client
  computes SHA-256 of what it sent; equal means confirmed. A suspend state records that revision.
- C2S `import_chunk` {offset, total, bytes ≤ 30000} — (new in 2) initial save for the pak this player just
  burned. Server accepts it once, within 120 s of the burn, only while the pak has no save, and only with a
  size of 512 B / 8 / 32 / 64 / 128 KiB (+16 byte RTC footer optional), at most 512 KiB; otherwise it answers
  with a message (and `save_ack` accepted=false).
- C2S `close` {pakId}
- C2S `burn` {romSha1, title, gameCode} — turn a held Blank Game Pak into a Pak for this ROM.
- S2C `link_state` {playerIndex, playerCount, names[]} — (count < 2 = unplugged)
- C2S `link_data` {bytes ≤ 64} → server → S2C `link_data` {fromIndex, bytes} to other members.

Revision guard: client-side suspend file `gbalink/suspend/<pakId>.state` = magic, version, revision, ROM SHA-1,
confirmed flag, core state. On `open` it is resumed only if its revision equals the `open` revision and the ROM
SHA-1 matches; otherwise it is deleted and the game cold-boots with the server save. On suspend the client
uploads the save if changed, waits for the `save_ack`, and records the revision from the ack (or the SHA-256 of
what it sent, flagged unconfirmed, if no ack arrives or the connection is gone).

`gba_get_save` returns mGBA's battery format: the raw SRAM/Flash/EEPROM image, plus a 16-byte RTC footer for
cartridges with a clock. `gba_get_save` resets the "dirty" flag.

## Items (vanilla-compatible, no registered items)

Vanilla base item + components: `custom_data` {gbalink:{type:"gba"|"pak"|"blank_pak"|"cable", ...}},
`item_model` "gbalink:<type>", `item_name`, `max_stack_size` 1 (cable may stack).
Pak data: {pakId UUID, sha1, title, code}. GBA may hold an inserted pak: {pak:{...}}.
Saves live server-side at `world/gbalink/saves/<pakId>.sav` and travel with the pak item.
