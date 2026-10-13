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

Protocol number: **3** (0.3.0; 2 was 0.2.0). Hello/Welcome carry it; mismatches never crash: the server refuses to `open`
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

Vanilla base item + components: `custom_data` {gbalink:{type:"gba"|"pak"|"blank_pak"|"cable"|"crt", ...}},
`item_model` "gbalink:<type>", `item_name`, `max_stack_size` 1 (cable may stack).
Pak data: {pakId UUID, sha1, title, code}. GBA may hold an inserted pak: {pak:{...}}.
Saves live server-side at `world/gbalink/saves/<pakId>.sav` and travel with the pak item.

## CRT TV (0.3.0, protocol 3)

One placeable item, type `crt` (base item `echo_shard`, inert in vanilla): a 90s Trinitron-style TV in a walnut cabinet on
casters, with a cube-shaped console + "Game Player" adapter on the open shelf and a controller on the floor in front
(single model, generic names, no logos). Vanilla base item + components like the others. The cabinet is **2 blocks
wide, 2 high, 1 deep**. The picture tube is 4:3 (about 1.4 blocks wide); the GBA's 3:2 image fills its width with thin
dark bars above and below.

**In the world** (no registered blocks/entities): using the CRT item on a block face spawns an `item_display` (item
model `gbalink:crt_placed`, `item_display:"fixed"`, facing the player) plus two 1x2 `interaction` entities, one per cell,
all sharing the tags `gbalink_crt` and `gbalink_crt_<crtId>`. The clicked block (the cell above the clicked face) is
the viewer's left cell, the second cell is on the viewer's right (`(cos Y, sin Y)` for TV yaw Y); all four cells must be
replaceable (air) and free of players/other TVs. The model is authored in x -16..16 so that the display entity's model
point (8,8,8) is the centre of the anchor cell; geometry constants live in `com.drew.gba.crt.CrtGeometry` (the screen
quad is the 3:2 picture rectangle 21.6 x 14.4 units at z 1.6) and are checked against `tools/gen_crt.py`.
State (crtId, yaw, `part` 0|1, inserted pak on part 0) lives in the interactions' custom data `data.gbalink.crt` and
persists with the world. A TV with yaw Y has its front facing (-sin Y, cos Y). Punching either interaction (any game
mode) removes all parts and drops exactly one CRT item with its pak. Vanilla clients see the static TV model.

**Playing:** sneak+use with a Pak in the offhand inserts it (sneak+use, empty offhand, ejects; refused while someone
plays). Use (not sneaking) → server `crt_open` → the client runs the normal `EmulatorSession` for the pak (same saves,
revisions, suspend, link cable) but in *CRT mode*: a transparent, non-pausing control screen captures the GBA keys (no
bezel GUI; Esc leaves = suspends; small hint line), the camera eases towards the screen, and the game renders onto the
TV in the world (own session: every rendered frame). Only one player can play a CRT at a time (server-enforced). The
server ends play (`crt_view` state 0 to the player) if the TV is broken or the player walks > 18 blocks away.

**Spectating:** the player's client encodes every 2nd emulated frame (~30 fps, never faster than one per 25 ms even
when fast-forwarding) with `com.drew.gba.stream.VideoEncoder` and audio (16 kHz mono IMA-ADPCM, 1092 stereo input
frames = 273 bytes per packet, ~8 KB/s) with `AudioEncoder` on a small encoder thread, and uploads `crt_stream`
packets (video packets over 30000 bytes are split into parts). The server relays them from the Netty thread (immutable
routing snapshot, like `link_data`) to modded viewers within `crt_view_distance` (default 48 blocks, same dimension)
up to `crt_max_viewers` (default 10) per CRT, nearest first, refreshed every second; extra viewers get a "viewer limit
reached" overlay on the TV. A streamer is limited to ~1 MB/s (token bucket; excess packets are dropped and a keyframe
is requested). Settings in `config/gbalink.properties` (`crt_max_viewers`, `crt_view_distance`, created with defaults).
Viewers decode with `VideoDecoder`/`AudioDecoder`, draw onto the TV face (nearest filtering, full bright, subtle
scanlines) and play the audio on their own 16 kHz stereo line (only the nearest watched TV is heard; linear fall-off,
silent beyond 24 blocks; stereo pan from the listener's facing; scaled by the master volume). New viewers and decoder
errors → keyframe request to the streamer (server-side rate limit: at most one per 0.5 s per TV); the encoder also emits
a keyframe every 60 frames. No input-replay spectating.

Payloads (new in 3):
- S2C `crt_open` {crtId, pakId, romSha1, title, save, revision, x, y, z, yaw} — like `open`, plus TV placement.
- C2S `crt_stream` {crtId, seq, kind (0 video, 1 audio), part, parts, bytes ≤ 30000} — a keyframe (up to ~75 KB) is
  split into several packets with the same seq and `part/parts`; the server forwards them unchanged.
- S2C `crt_stream` {crtId, seq, kind, part, parts, bytes} — to viewers.
- C2S `crt_need_keyframe` {crtId} — a viewer could not decode; the server forwards a rate-limited `crt_keyframe_request`.
- S2C `crt_keyframe_request` {crtId}
- S2C `crt_view` {crtId, state (0 none/idle, 1 playing, 2 limit reached), playerName, x, y, z, yaw} — viewers
  learn which TVs are live near them (x,y,z = bottom centre of the anchor cell); also sent when a stream ends. State 0
  sent to the player who is playing means the server ended their play.
- C2S `crt_close` {crtId} — the player leaves the TV (the pak `close` follows once the save is flushed).

### Stream codec contract (`com.drew.gba.stream`, pure Java, no Minecraft classes)

```java
VideoEncoder(): byte[] encode(byte[] rgba /* 240*160*4 */, boolean forceKeyframe) // one packet per frame
VideoDecoder(): boolean decode(byte[] packet, byte[] rgbaOut)  // false = can't decode, need keyframe
                boolean needsKeyframe()
AudioEncoder(): byte[] encode(short[] stereo32k, int frames)   // 32768 Hz stereo in, 16 kHz mono ADPCM out
AudioDecoder(): int decode(byte[] packet, short[] mono16kOut)  // returns samples written
```
Packets are self-describing (header with type/keyframe flag); the decoder keeps state across in-order packets.
Targets at 30 fps: static ≈ 0, typical ≈ 10–30 KB/s, worst (full-screen scroll/flashes) ≤ ~300 KB/s.
