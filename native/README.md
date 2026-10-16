# gbacore - native GBA / Game Boy / Game Boy Color emulator library

`libgbacore` wraps **mGBA 0.10.5** (the GBA and the Game Boy / Game Boy Color cores, built statically into the library)
behind the C ABI in `include/gbacore.h` (contract: `../SPEC.md`, API version 4) and adds a serial-level
**GBA link cable** that works across processes: the host only forwards opaque byte messages.

## Build

Needs CMake >= 3.16, a C compiler, Python 3 (generates the link test ROMs) and the git submodules
(`git submodule update --init --recursive` from the repository root).

```sh
cd native
./run_tests.sh                       # configure + build + run all tests (ctest) for this machine
./build_target.sh macos-aarch64      # one platform -> ../dist/natives/<platform>/ (add --test to run ctest)
./build_all.sh                       # everything this machine can build
```

`cmake` is taken from `PATH`; set `CMAKE=/path/to/cmake` to override (all scripts accept paths with spaces).
For local development a CMake bundled in `../tools/cmake` (git-ignored) is used as a fallback.

Result: `build-<platform>/` plus a post-build copy to `../dist/natives/<platform>/`
(`-DGBA_DIST_DIR=...` or `GBA_DIST_ROOT=...` override). Platform names: macos-x86_64, macos-aarch64,
windows-x86_64, linux-x86_64.

Third-party sources (not modified): `third_party/mgba` (tag 0.10.5, git submodule),
`third_party/gba-tests` (jsmolka, MIT, git submodule, test ROMs only used by the tests).

### Platforms (`build_target.sh`)

* **macos-aarch64 / macos-x86_64**: Apple clang with `-DCMAKE_OSX_ARCHITECTURES=arm64|x86_64
  -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0`, then ad-hoc signed (`codesign -s -`, required on Apple Silicon).
  Tests can only run for the host architecture.
* **windows-x86_64** and **linux-x86_64**: Zig (0.17, `ZIG=...`, else `../tools/zig/zig`, else `zig` on
  `PATH`) through `cmake/zig-<platform>.cmake` and the wrapper scripts `cmake/zig-*.sh`.
  Windows targets `x86_64-windows-gnu` (Zig's mingw is linked statically: the DLL imports only KERNEL32,
  SHELL32, ole32, SHLWAPI and the Windows UCRT api-set DLLs, part of Windows 10+; no mingw runtime DLLs;
  no `lib` prefix, `gbacore.dll`). Linux targets `x86_64-linux-gnu.2.17` (glibc 2.17 or newer) and is
  stripped. Only `gba_*` is exported. The Windows build is cross-compiled only (DLL target only); the
  Linux build can run its tests on a Linux x86_64 host.
* Cross compiling: nothing host specific is assumed. Pass a CMake toolchain file. On Windows the
  exports come from `__declspec(dllexport)` (`GBACORE_BUILD_SHARED`), on Linux from
  `src/exports.map`, on macOS from `src/exports.macos.txt`. mGBA is built with
  `-fvisibility=hidden`, so only `gba_*` symbols are exported (checked by the `exports` test).

### What is enabled / disabled in mGBA

Enabled: the GBA core and (since 0.5.0) the GB/GBC core (`M_CORE_GB`, about 170 KB more per library, no new dependencies).
Disabled:

Qt/SDL frontends, OpenGL, debuggers + GDB stub, editline, ffmpeg, libzip/minizip,
zlib/png (no compressed ROMs/states, no screenshots), sqlite, lua/scripting, ELF loader, LZMA/7z,
Discord RPC (`DISABLE_DEPS`, `LIBMGBA_ONLY` via `add_subdirectory(... EXCLUDE_FROM_ALL)`). No BIOS
file is used: mGBA's built-in HLE BIOS runs. The only extra flag is `-fwrapv`: mGBA computes ARM/Thumb
flags with signed overflow, which modern clang otherwise miscompiles (jsmolka `thumb.gba` failed at
test 102 without it).

## Behaviour notes

* Video: 240x160 (GBA) or 160x144 (Game Boy), see `gba_frame_width/height`; bytes in memory R,G,B,A (A forced to 255). Valid until the
  next `gba_run_frame`.
* Audio: 32768 Hz stereo int16. mGBA delivers 4-sample blocks, so single frames carry 548 or 552
  sample frames (steady-state average 548.6). Unread audio is kept in a 16384-frame ring (oldest
  dropped on overflow). Link waits do not lose audio.
* ROMs are read into memory and loaded directly with `GBALoadROM`; mGBA's "multiboot" detection
  (which misdetects tiny homebrew as EWRAM images) is bypassed.
* Saves: the core's save lives in a memory file. `gba_get_save` returns mGBA `.sav` layout (RTC
  appended when the cart has one). `gba_save_size` is 0 until the game has touched its save
  hardware (mGBA autodetects the type lazily) unless a save was loaded with `gba_load_save`.
  `gba_save_dirty` compares a hash against the last `gba_get_save`/`gba_load_save`.
* States: raw `mCore::saveState` blob (no compression, no screenshot), size from `gba_state_size`.
  In-flight link transfers are not part of a state: loading a state or resetting aborts them.
* Logging from mGBA is discarded.

## Game Boy and Game Boy Color (API version 4)

`gba_load_rom` reads the whole file, asks mGBA's own header check (`mCoreIsCompatible`: the Game Boy logo at 0x104) and builds the matching
core: GBA ROMs and anything unrecognised load as GBA exactly as before; Game Boy ROMs (up to 8 MiB) get a **fresh GB core per load**
(mGBA keeps the detected hardware model across loads). `gba_platform` is 0 for GBA, 1 for GB/GBC; `gba_frame_width/height` give the
picture size (160x144); the framebuffer format is the same RGBA bytes.

* **Model and options.** No BIOS (`skipBios`, `useBios = false`). The model is mGBA's autodetect: CGB flag -> Game Boy Color, else
  DMG. Super Game Boy borders are off (`sgb.borders` false) and `sgb.model = DMG` makes SGB-flagged games plain DMG, so the picture is
  always 160x144. DMG games get a green palette (`0xE0F8D0, 0x88C070, 0x346856, 0x081820`, set through `GBVideoSetPalette` for BG, OBJ0 and OBJ1
  and re-applied after every reset); Game Boy Color games use mGBA's colours unchanged (no colour correction).
* **Keys.** The GBA key mask maps to the Game Boy buttons: bits 0-7 are A, B, Select, Start, Right, Left, Up, Down (the same order on both
  systems), L / R (bits 8, 9) are dropped. mGBA does not allow Left+Right or Up+Down together (default `allowOpposingDirections`).
* **Audio** goes through the same blip resampler to 32768 Hz stereo; a frame is 70224 cycles at 4194304 Hz (59.7275 fps), about 548.7 stereo frames.
* **Saves.** The save lives in a memory file like on GBA. `gba_save_size` = cartridge RAM size (known after load) plus the clock footer for
  MBC3+RTC (48 bytes), HuC3 or TAMA5 carts; `gba_get_save` writes the footer first (`GBMBCRTCWrite`) so the clock is current. The
  RTC footer follows VBA / mGBA: five current and five latched registers and the UNIX time of the last latch; mGBA restores the clock registers from the
  latched copy. Carts without battery RAM have size 0.
* **RTC.** `gba_set_rtc` works for MBC3 carts (mGBA's generic RTC source; both modes). Because the cartridge records the time of the last latch
  when the ROM is loaded, switching the source moves that time by the same amount, so the in-game clock does not jump; a save loaded afterwards
  restores its own value. A Game Boy clock is UNIX based (no time zone conversion); virtual mode still forces `TZ=UTC` for the process like on GBA.
* **States** are raw mGBA states, `gba_state_size` has no group trailer (a state with a 128 byte trailer is rejected for a Game Boy core).
* **GBA-only APIs.** `gba_link_configure` / `gba_link_deliver` are ignored, `gba_link_poll_outgoing` returns 0 and `gba_group_add` fails with -1 for a
  Game Boy core; the lockstep and link code never runs for them. Loading a ROM into a core that is a member of a group dissolves the group first.
* Tests: `gb` (boot, frames, framebuffer shades / colours, audio rate and tone, keys, save + RTC footer round trips, state round trip and replay, reset,
  virtual RTC, platform switching, refusal of link / group calls), `determinism_gb`, and the manual harness `gb_harness` (screenshots of any ROM). The
  ROMs are written by `tests/make_gb_rom.py` (hand assembled LR35902: tile graphics, text, a 440 Hz tone, MBC3+RTC / MBC5 battery RAM,
  joypad reads); `gb_rom_in_sync_*` checks that the copies the Java tests use (`mod/src/test/resources/roms`) are what the generator writes.

## Virtual RTC (API version 2)

`gba_set_rtc(core, epoch_seconds, mode)`: mode `GBA_RTC_WALL` (0, default) leaves mGBA's wall clock and the local time
zone in charge. Mode `GBA_RTC_VIRTUAL` (1) switches the generic RTC source to `RTC_FAKE_EPOCH`: the cartridge clock reads
`epoch_seconds` (UNIX time) at the moment of the call and then advances with *emulated* time (`frameCounter` x 280896
cycles at 16777216 Hz), so two cores that execute the same frames see the same time on every machine. Call it after
`gba_load_rom` and before `gba_load_save` / `gba_load_state`; loading a state keeps the virtual clock where it was (the
state carries its own frame counter).

mGBA converts the clock to the cartridge's BCD registers (and computes the save-file time offset) with `localtime_r` /
`mktime`, i.e. in the process time zone. While at least one core is in virtual mode the shim therefore sets `TZ=UTC` for the
process (reference counted behind a spin lock, restored when the last such core leaves virtual mode or is destroyed; Java
keeps its own zone data). Returning to `GBA_RTC_WALL` recomputes the save-time offset in the local zone so the in-game
clock continues instead of jumping by the UTC offset.

Determinism helpers for netplay: loading a state (and `gba_load_rom` / `gba_reset`) sets the link bookkeeping that is not part
of a state to fixed values (`ranSinceDone`, pending/settle flags, sequence numbers, message queue), and `gba_save_state`
zeroes its output buffer first so states can be hashed. Version 1 libraries lack `gba_set_rtc`; the Java binding still
loads them (no netplay).

## Lockstep groups (API version 3)

`gba_group_create(n)`, `gba_group_add(group, core)` (player order; ROM, save, state and virtual RTC first),
`gba_group_run_frame(group)`, `gba_group_destroy(group)`. Used by input-sync netplay; all cores live in one process.

The link is mGBA's own `GBASIOLockstep` + `mLockstep`: the code behind the multiplayer windows of mGBA's Qt frontend, which
is known to do Pokemon Gen 3 trades and battles. It was written for one thread per core. Its `mLockstep` callbacks do not
block there either: the master posts "cycles you may run" to the followers (`addCycles`), followers stop when they have used
them (`useCycles`), the master stops at the two sync points of a transfer until the followers signal (`wait` / `signal`).
Here the callbacks in `gbacore.c` mirror `MultiplayerController.cpp`, and "stopped" simply means the scheduler does not step
that core. `gba_group_run_frame` loops in fixed rounds: every awake core in index order does one `ARMRunLoop` (up to its
next timing event); a core that finished its video frame only runs on when the master waits for it; the call returns when
every core completed one frame. Nothing depends on time or threads, so it is deterministic and identical on every platform.
The master may run up to ~2000 cycles (one lockstep increment) ahead of the followers.

* Each core's finished frame is copied, so `gba_framebuffer` is stable.
* Save states of grouped cores get a 128 byte trailer (`gba_state_size` includes it): node fields (`nextEvent`, `eventDiff`,
  mode, scheduled event delta), the scheduler state (awake, posted cycles, wait mask), the lockstep phase and receive
  buffers, plus the live SIOCNT/RCNT and SIOMLT_SEND (an mGBA state stores only the raw IO copies and omits write-only
  registers) and the CPU `nextEvent` is recomputed on load. States with/without trailer load into grouped/ungrouped cores
  (a trailer is applied when the last core joins; without one the link restarts idle). `test_group` proves a reload at any
  frame continues bit-identically.
* A member that is destroyed or reloaded dissolves the group (the others get the classic driver back, idle).
* Limits: mGBA's lockstep gives a Normal-mode master 0xFFFFFFFF and has the followers finish a transfer early (about one
  transfer time before the master); Multiplayer mode is complete. UART/JOY/GBA-Player are not supported.

## Link cable (classic mode, network between different processes)

The old driver below is only used by classic mode (players with different ROMs). It is NOT accepted by Pokemon Emerald's
Cable Club (see CHANGELOG 0.4.2, `tests/emerald_harness.c`): the SD/SI approximations and completion at frame boundaries
differ from real hardware timing. Classic mode has the same risk over a network.

Implemented as a custom `GBASIODriver` for Normal-8, Normal-32 and Multiplayer (2-4 players),
modelled on `src/gba/sio/lockstep.c` but with messages instead of shared memory.

`gba_link_configure(core, idx, count)`: `count < 2` (or invalid) = unplugged. Identical
reconfiguration is a no-op; any change aborts transfers in flight (see below).

### Flow of one transfer

1. **Master** (player 0) writes SIOCNT with the start bit (Normal: internal clock; Multi: start).
   The shim latches SIODATA/SIOMLT_SEND, queues a `START` message and makes `gba_run_frame` return
   `GBA_RUN_LINK_WAIT` right after that instruction (emulated time is frozen). Further calls
   return `LINK_WAIT` immediately until all replies are in. The game still sees start/busy = 1.
2. **Children** receive `START`. Their emulation continues; after `SETTLE_CYCLES` (2048 emulated
   cycles, so interrupt handlers of the previous transfer ran) the child latches its outgoing data
   and queues a `REPLY`. Multiplayer children always take part (busy bit set, SIOMULTI = 0xFFFF
   meanwhile). In Normal mode the child only replies once the game armed the transfer (start bit set,
   external clock) - like a real slave holding the line. If its mode does not match, or after 180
   frames unarmed, it replies "no data".
3. When the master has every reply it queues `DONE` (all players' data + validity mask) and
   schedules the transfer end after the hardware transfer time (Normal: 64/512 cycles per 8 bits;
   Multi: mGBA's baud/player table). Then it completes: SIOMULTI0-3 / SIODATA32 / SIODATA8 written,
   start bit cleared, SIO IRQ raised if enabled; the **same frame continues** on the next
   `gba_run_frame`.
4. Children complete on `DONE`: multiplayer: SIOMULTI0-3 = all players' data (0xFFFF for absent),
   ID bits = own index, busy cleared; Normal: receive the previous player's word (daisy chain:
   player k gets player k-1's data, the master gets the last player's). IRQ if enabled.

No partner (unplugged / unplug while waiting / reply "no data"): Multi slots read 0xFFFF, Normal-32
reads 0xFFFFFFFF, Normal-8 reads 0xFF; the master's own SIOMULTI0 holds what it sent.
`gba_link_configure` while waiting unblocks the master (its transfer completes as no-partner after
the normal transfer time); a child busy or pending when reconfigured completes with no data.

SIOCNT bits: Multi: bit 2 (SI) = 1 for children and when unplugged, bit 3 (SD, "all ready") = 1 when
plugged and no peer is *known* to be outside link mode, bits 4-5 = player id, bit 7 busy, RCNT bit 0/2
updated like mGBA's lockstep. Normal: SI (bit 2) = 0 when plugged, 1 when unplugged.

### Message format (little endian, all <= 24 bytes, `GBA_LINK_MSG_MAX` = 64)

| type | name  | layout |
|------|-------|--------|
| 1 | START | `[1][src][seq][mode][data32][count]` master -> all (9 bytes) |
| 2 | REPLY | `[2][src][seq][mode][data32][valid]` child -> all (9 bytes) |
| 3 | DONE  | `[3][src][seq][mode][validMask][0][0][0][data32 x4]` master -> all (24 bytes) |
| 4 | MODE  | `[4][src][0][mode]` presence: mode 0=Normal8 1=Normal32 2=Multi 0xFF=other (4 bytes) |

`mode` is mGBA's `GBASIOMode` (0, 1, 2). `seq` increments per master transfer; stale replies are
ignored. Every message is meant to be broadcast to all other players; receivers ignore what is not
for them. MODE messages are emitted at the end of a frame when a core's SIO mode changed (and on
configure) and feed the "all ready" bit.

## Tests (`tests/`, `ctest` or `./run_tests.sh`)

* `headless_*`: jsmolka ROMs, 300 frames, audio count per frame, framebuffer hash, RGBA byte
  order, state save/load round trip, SRAM save API, two independent cores, reset, keys.
* `link`: 2-4 cores in one process behind an in-memory relay with 0-7 ticks of latency, test ROMs
  built by `tests/make_link_rom.py` (hand assembled ARM, see its docstring): Normal-32/8 pairing and
  ordering in both directions, 3-player daisy chain, IRQ completion, Multiplayer with 2/3/4 players
  (SIOMULTI0-3, ID bits, IRQ), mode mismatch, unplug while the master waits (all three modes),
  unplug of a busy child, re-plug.
* `rtc`: virtual RTC reading, advancing with frames, kept across `gba_load_state`, time zone forced to UTC while virtual
  and restored afterwards.
* `group`: lockstep groups (Normal 8/32, Multiplayer 2-4, the `gen3` ROM with timer-IRQ transfers and SD/SI/ID checks, pokesim,
  mode mismatch, state reload equivalence, dissolve).
* `emerald_harness` (not in ctest): boots two cores from a ROM and saves in `gba/test-roms/`, scripts input, writes PNG screenshots.
* `determinism`: two cores in a lockstep group (what netplay uses), fixed inputs, virtual RTC;
  pins the CRC32 of both complete states at frames 100/1000/1500 (the same values the Java check prints on every CI platform).
* `gb`, `determinism_gb`, `gb_rom_in_sync_*`: Game Boy / Game Boy Color, see above. `gb_harness` is a manual tool for real cartridge dumps.
* `dylib`: `dlopen`s the real shared library, checks that only `gba_*` is visible and runs a ROM.
* `exports`: `nm` check.

mGBA 0.10.5 itself fails some exotic jsmolka tests (listed in `tests/CMakeLists.txt`); the tests pin
the exact final screens so regressions are detected.

## Known limitations

* A transfer costs at least one network round trip plus up to one child frame; the master is
  blocked meanwhile. Games that transfer every frame run at about the round-trip rate.
* "All ready" (SD) is an approximation (see above). Peers that never announced a mode count as ready.
* Normal mode with 3-4 players uses the daisy chain data flow but its SO/SI handshake bits are not
  emulated beyond SI = 0. UART, JOY bus, GBA-Player and Wireless Adapter modes are not supported
  (mGBA's default dummy drivers apply).
* Link state is not serialized in save states (loading a state resets it to fixed values, see above).
* No GBA BIOS file is used (HLE), so BIOS-timing-sensitive titles/tests (jsmolka `bios.gba`) differ.
* mGBA 0.10.5 ignores unaligned 16-bit stores to SRAM (hardware quirk, fails jsmolka save test 6).
