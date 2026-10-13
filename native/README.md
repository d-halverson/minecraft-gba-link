# gbacore - native GBA emulator library

`libgbacore` wraps **mGBA 0.10.5** (GBA core only, built statically into the library) behind the
C ABI in `include/gbacore.h` (contract: `../SPEC.md`, API version 1) and adds a serial-level
**link cable** that works across processes: the host only forwards opaque byte messages.

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

### What is disabled in mGBA

GB/GBC core, Qt/SDL frontends, OpenGL, debuggers + GDB stub, editline, ffmpeg, libzip/minizip,
zlib/png (no compressed ROMs/states, no screenshots), sqlite, lua/scripting, ELF loader, LZMA/7z,
Discord RPC (`DISABLE_DEPS`, `LIBMGBA_ONLY` via `add_subdirectory(... EXCLUDE_FROM_ALL)`). No BIOS
file is used: mGBA's built-in HLE BIOS runs. The only extra flag is `-fwrapv`: mGBA computes ARM/Thumb
flags with signed overflow, which modern clang otherwise miscompiles (jsmolka `thumb.gba` failed at
test 102 without it).

## Behaviour notes

* Video: 240x160, bytes in memory R,G,B,A (A forced to 255). Valid until the next `gba_run_frame`.
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

## Link cable

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
* Link state is not serialized in save states.
* No GBA BIOS file is used (HLE), so BIOS-timing-sensitive titles/tests (jsmolka `bios.gba`) differ.
* mGBA 0.10.5 ignores unaligned 16-bit stores to SRAM (hardware quirk, fails jsmolka save test 6).
