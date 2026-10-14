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

uint32_t    gba_api_version(void);              /* returns 2 for this spec (1 = without gba_set_rtc) */
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

/* ---- Virtual RTC (API version 2) ----
   GBA_RTC_WALL 0 (default): wall clock + local time zone. GBA_RTC_VIRTUAL 1: the cartridge clock reads epoch_seconds
   (UNIX, UTC) at the time of the call and advances with emulated time (frameCounter * 280896 cycles / 16777216 Hz);
   time zone ignored. Call after gba_load_rom and before gba_load_save/gba_load_state. 0 ok, <0 bad argument. */
#define GBA_RTC_WALL    0
#define GBA_RTC_VIRTUAL 1
int         gba_set_rtc(gba_core*, int64_t epoch_seconds, int mode);

/* ---- Lockstep groups (API version 3, 0.4.2) ----
   Cores of one process linked with mGBA's own GBASIOLockstep, stepped by the library on the calling thread in a fixed
   order (deterministic, no messages, no LINK_WAIT). gba_group_create(n), gba_group_add(group, core) in player order
   (ROM/save/state/RTC loaded first), gba_group_run_frame(group) = one frame for every core (each core's framebuffer
   holds its completed frame), gba_group_destroy (cores get their classic driver back), gba_group_stalls (diagnostic).
   A grouped core cannot gba_run_frame; its states carry a 128 byte lockstep trailer. See native/README.md. */
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

**Audio output (client).** One JavaSound line per output (emulator 32768 Hz stereo, CRT spectators 16 kHz stereo) is
opened once and never stopped or closed (closing can hang on macOS). `emu/LineFeeder` keeps each line well behaved: audio
is faded in over ~10 ms after a pause; when the writer stalls (> 22 ms) and the real audio is about to run out it queues a
~10 ms ramp to zero and then keeps the line topped up with zeros (~60 ms), so an idle line never underruns and never
replays stale buffer contents. It is tested against a fake device (`LineFeederTest`).

## Network protocol (Fabric custom payloads, namespace `gbalink`)

Only sent to clients where `ServerPlayNetworking.canSend` is true. Vanilla / non-gbalink
clients must be able to join and see the items (no new synced registry entries, no custom
command argument types).

Protocol number: **4** (0.4.0; 3 was 0.3.0, 2 was 0.2.0). Hello/Welcome carry it; mismatches never crash: the server refuses to `open`
for a client with a different protocol ("update the mod"), and a client treats a server that sends no `welcome`
(protocol 1) as unable to do revisions/imports and shows "update the mod". New fields are appended last and
decoded tolerantly.

- C2S `hello` {protocol, caps} — client announces the mod and its protocol. `caps` (new in 0.4.0, appended; 0 if absent)
  is a bit set: bit 0 = netplay with the 0.4.0/0.4.1 link driver (native API 2, no longer used for new sessions), bit 1 (`CAP_NETPLAY_LOCKSTEP`, 0.4.2) = the client can run netplay with mGBA lockstep groups (native API version 3).
- S2C `welcome` {protocol} — server's protocol (new in 2; only sent if the client can receive it).
- S2C `open` {pakId UUID, romSha1 hex, title, save bytes, revision} — open emulator for this pak.
  `revision` (new in 2, appended; "" if absent) = lowercase hex SHA-256 of `save` (a missing save is the hash
  of zero bytes). The server only sends `open` to protocol-2 clients.
- C2S `save_chunk` {pakId, offset, total, bytes ≤ 30000} — client uploads save
  (serverbound payloads are limited to 32 KiB). Server assembles and writes atomically (at most ~5 saves/s).
- S2C `save_ack` {pakId, revision, accepted, message} — (new in 2) sent after every completed upload or import.
  `revision` is the SHA-256 of what the server now stores (unchanged if `accepted` is false). The client
  computes SHA-256 of what it sent; equal means confirmed. A suspend state records that revision.
- C2S `import_save` {pakId, offset, total, bytes ≤ 30000} — (new in 4; replaces 2/3's burn-time `import_chunk`)
  replace the save of the pak this player **currently has open** (an open session for exactly that pak, GBA or CRT)
  with an imported one. Same chunking as `save_chunk`, rate limited together with uploads. Accepted only with a size of
  512 B / 8 / 32 / 64 / 128 KiB (+16 byte RTC footer optional), at most 512 KiB (`SaveRules.importRejection`); the
  previous save is kept as `<pakId>.sav.bak1` (older ones shift to `.bak2`, `.bak3`, the oldest is dropped) and any
  half-finished normal upload is dropped. The server always answers with `save_ack` (accepted=false and the unchanged
  revision if refused, e.g. no open session or a bad size). Imports for a pak the player does not have open are ignored.
- C2S `close` {pakId}
- C2S `burn` {romSha1, title, gameCode} — turn a held Blank Game Pak into a Pak for this ROM.
- S2C `link_state` {playerIndex, playerCount, names[], mode} — (count < 2 = unplugged). `mode` (new in 0.4.0, appended; 0 if
  absent): 0 = classic link, 1 = netplay (the classic cable stays unplugged, `netplay_start` follows).
- S2C `cable_state` {myIndex, count, (name, open)[]} — **optional, additive (0.4.2)**: only sent to clients that registered
  it (protocol 4 clients without it are unaffected). Who is on my link cable even with no GBA open (`open` = that member
  has a GBA/CRT running); count 0 / myIndex -1 = not cabled. Sent on every room change or session change in the room, on
  Hello (join) and after respawn. Drives the client's cable HUD indicator.
- C2S `unlink` {} — **optional (0.4.2)**: take me off the cable (same as `/gba unlink`); the other members get the action
  bar message "<name> unplugged the link cable."
- C2S `link_data` {bytes ≤ 64} → server → S2C `link_data` {fromIndex, bytes} to other members.

Revision guard: client-side suspend file `gbalink/suspend/<pakId>.state` = magic, version, revision, ROM SHA-1,
confirmed flag, core state. On `open` it is resumed only if its revision equals the `open` revision and the ROM
SHA-1 matches; otherwise it is deleted and the game cold-boots with the server save. On import (client): the current save is exported to `exports/<game>-<time>-before-import.sav`, the new one is uploaded
with `import_save`, and on an accepting `save_ack` (revision equals the SHA-256 of what was sent) the suspend file for that
pak is deleted, the local mirror is rewritten and the core is reloaded from the imported save (cold boot, same session).
On suspend the client
uploads the save if changed, waits for the `save_ack`, and records the revision from the ack (or the SHA-256 of
what it sent, flagged unconfirmed, if no ack arrives or the connection is gone).

`gba_get_save` returns mGBA's battery format: the raw SRAM/Flash/EEPROM image, plus a 16-byte RTC footer for
cartridges with a clock. `gba_get_save` resets the "dirty" flag.

## Netplay link (0.4.0, protocol 4)

**Why.** The classic link (above) is lockstep: the master core blocks for a network round trip on every serial transfer
(measured 17 fps at 50 ms RTT, 9 fps at 100 ms; the games' own link checks fail at 50 ms and more). Netplay instead
makes every linked client simulate ALL linked consoles locally, in one thread, linked by mGBA's own lockstep (0.4.2: `gba_group_*`,
`NetplaySim`; 0.4.0/0.4.1 used the custom link driver, which failed Pokemon Emerald's Cable Club with "Communication error"); only controller inputs cross the network, with an adaptive input delay.

**Mode selection (server, `GbaServer.sendRoom`, `EffectiveLink.netplay`).** The effective link is the room's members with
an open emulator session (GBA or CRT). Netplay when it has at least 2 players, every client announced `CAP_NETPLAY_LOCKSTEP`, and
every pak has the same ROM SHA-1 (remembered per open session). Otherwise the classic link ("Link (different games -
slow mode)"). `link_state.mode` tells the clients which; for netplay the server then sends `netplay_start` to every member.
Any change of the effective link (join, leave, close, cable break, distance, disconnect) sends a new `link_state` and, if
at least two players remain with the same ROM, a new `netplay_start` with a new session id (the old session is dead).

**Session start.**
1. S2C `netplay_start` {sessionId (varlong), index, count, names[], epoch (long, server UTC seconds), tzOffset (int, server
   zone offset), delay (initial input delay, 3)}. index = effective player index = console index (0 is the link master).
2. The client stops its core at a frame boundary and takes state + battery save of its own console, builds the blob
   (below), deflates it and uploads it with C2S `netplay_snapshot`; the server relays each chunk to the other members as
   S2C `netplay_snapshot` (with `from`). "Connecting link..." is shown meanwhile; after 15 s without all snapshots the
   client falls back to a standalone game with a message.
3. With all snapshots, the client creates FRESH cores for every console (its own too: link state outside the save state
   would otherwise differ between machines), loads ROM, `gba_set_rtc(epoch + tzOffset + frame*280896/16777216, VIRTUAL)`,
   save, state, joins them in a lockstep group (`gba_group_add`, player order), and starts at netplay frame 0 (the virtual clock therefore shows the
   server's local time).

Snapshot blob (deflate level 1 of): `int 0x4E505331, int frame, int delay, byte n, n x { byte player, int stateLen, state,
int saveLen, save }`. Start: one entry (the sender's console, frame 0). Resync: all consoles, from player 0, frame = the
netplay frame about to run, delay = the delay in effect. Chunks are at most 30000 bytes, in order; total at most 4 MiB.

**Running.** Frame f runs when the records of all players for f are known.
- C2S `netplay_input` {sessionId, firstFrame, recs[], sendMs, echo[]}; the server relays it from the Netty thread (like
  `link_data`, no tick wait) as S2C `netplay_input` {sessionId, from, ...}; the client receives it on the network thread
  (`ClientNetplayFastPathMixin`). One record is a short: keys in bits 0-9 and the sender's delay proposal in bits 10-13. A
  packet carries the last 3 records (redundancy). At frame s a player samples its keys and sends the record for frame
  s + delay; raising the delay fills the gap with the new sample, lowering it skips samples; records below the initial delay
  are implicit (no keys, proposal = initial delay). First record wins.
- `echo` is a flat list of (player, theirSendMs, holdMs) triples: the sender's last received timestamp from each peer and
  how long it held it. The receiver computes RTT = now - theirSendMs - holdMs.
- Stepping (identical on every machine, versioned with the protocol): inputs set for every console; core 0 runs; whenever it
  returns LINK_WAIT its messages are delivered to cores 1..n-1 in index order, their immediate replies back to core 0; if core
  0 is still stuck every other core runs a frame in index order (core 0 gets their replies after each); cores that did not
  run yet run after core 0 finishes; the other consoles' audio is drained and discarded; everything in flight is delivered.
- Display, audio and the CRT stream use only the local console. Pacing 59.7275 fps; after a stall the client may catch up
  (if more than 100 ms behind it skips ahead instead). Stalls are counted; 10 s without a partner's records fails the session.
- **Adaptive delay.** Proposal = ceil((p99 RTT over 5 s / 2 + 8 ms) / 16.74 ms), clamped to 2-10 frames, raised at once on a
  stall (at most once per second), lowered by one after 10 s without stalls. The delay actually used is the maximum of all
  proposals found in the records of the frame just simulated, applied from the next frame, so every client switches at the
  same simulated frame.
- **Desync.** After every 60th frame each client sends C2S `netplay_hash` {sessionId, gen, frame, hash} (relayed as S2C
  with `from`); hash = CRC32 over the complete save states of all consoles in index order. A mismatch is logged to
  `link.log` (and a toast shown); player 0 then snapshots all consoles and sends them as `netplay_snapshot` with gen+1 and the
  frame; every client reloads fresh cores from it and continues from that frame (clients that were already further replay
  the stored inputs). A client that waits more than 10 s for a resync snapshot, or fails otherwise, may send C2S
  `netplay_restart` {sessionId}; the server restarts the session for everybody (at most every 3 s).
- **Saves.** Every client uploads only its own console's save (the normal save/revision/ack path, every 10 s when dirty).
  Closing the GBA leaves the link first, so the suspend state is the local console's state.
- **Leaving.** The server ends the session for everyone on any change of the effective link; a client whose session is not
  continued (link_state unplugged or classic, failure, timeout) turns its local simulation core back into the normal
  standalone session, keeping its state, cable unplugged, wall-clock RTC again.
- **Disabled while netplay is active** (toast): fast-forward, save/load state, import. The power switch closes the session
  (which leaves the link first).

Payloads (new in 0.4.0; protocol number unchanged at 4 for this release):
- S2C `netplay_start`, C2S/S2C `netplay_input`, `netplay_hash`, `netplay_snapshot` (S2C adds `from`), C2S `netplay_restart`.
- The server rate-limits relayed input/hash packets (token bucket, 5000/s) and snapshot uploads (6 MiB burst, 2 MiB/s).
- Determinism requirements on the native side: see native/README.md (virtual RTC, state load bookkeeping, zeroed states).
  `-Dgbalink.relaydelay=<ms>` (server, dev) delays relayed netplay and link packets by that many ms one way.

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
bezel GUI; Esc leaves = suspends; small hint line; the crosshair, held item and HUD are hidden like F1 for the duration and the previous F1 state is
restored on leaving, see `ui/HudHider`; `E` exports and `I` imports the save, the import screens open on top and give the
session back), the camera eases towards the screen, and the game renders onto the
TV in the world (own session: every rendered frame). Only one player can play a CRT at a time (server-enforced). The
server ends play (`crt_view` state 0 to the player) if the TV is broken or the player walks > 18 blocks away.

**Spectating:** the player's client encodes every 2nd emulated frame (~30 fps, never faster than one per 25 ms even
when fast-forwarding) with `com.drew.gba.stream.VideoEncoder` and audio (16 kHz mono IMA-ADPCM, 1092 stereo input
frames = 273 bytes per packet, ~8 KB/s) with `AudioEncoder` on a small encoder thread, and uploads `crt_stream`
packets (video packets over 30000 bytes are split into parts). The server relays them from the Netty thread (immutable
routing snapshot, like `link_data`) to modded viewers within `crt_view_distance` (default 48 blocks, same dimension)
up to `crt_max_viewers` (default 10) per CRT, nearest first, refreshed every second; extra viewers get a "viewer limit
reached" overlay on the TV. A streamer is limited to ~1 MB/s (token bucket; excess packets are dropped and a keyframe
is requested). Settings in `config/gbalink.properties` (`crt_max_viewers`, `crt_view_distance`, `crt_audio_distance`, created with defaults).
Viewers decode with `VideoDecoder`/`AudioDecoder`, draw onto the TV face (nearest filtering, full bright, subtle
scanlines) and play the audio on their own 16 kHz stereo line (only the nearest watched TV is heard; full volume up to 4
blocks, then a fall-off to silence at `crt_audio_distance` (default 24); stereo pan from the listener's facing; scaled by
the TV volume (the playing player's GBA knob, `crt_volume`) and the listener's Minecraft master volume, not by the
listener's own GBA knob). Audio is **not** limited by the viewer cap: players over `crt_max_viewers` still receive the
audio packets (`kind` 1) while within `crt_audio_distance` (effective value never above `crt_view_distance`). New viewers and decoder
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
- C2S/S2C `crt_volume` {crtId, percent} — **optional (0.4.2)**: the TV volume = the playing player's GBA volume knob. The
  player sends it on start and on every change; the server relays it to everyone who hears the TV (new viewers get it
  with their first `crt_view`), so the player and the audience hear the same level. Missing = 100.
- S2C `crt_audio_range` {blocks} — **optional (0.4.2)**: `crt_audio_distance`, where the TV becomes silent.
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
