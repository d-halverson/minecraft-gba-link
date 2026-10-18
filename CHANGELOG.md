# Changelog

## 0.5.1

* **The Link Cable works for Game Boy and Game Boy Color games**, the same item and the same steps as for GBA (link to a nearby player,
  Unlink, "Linked to X" HUD, confirm before leaving a link): the games' own link menus work over the Minecraft server. Pokemon Red's
  Cable Club, Trade Center and a complete trade between two real clients were verified (same ROM, netplay). Pairs that can link:
  GBA with GBA (2 to 4), Game Boy / Game Boy Color with Game Boy / Game Boy Color (2). A Game Boy game never links with a GBA game:
  both players get "Can't link: X plays a Game Boy Advance game, you play a Game Boy / Color game" and keep playing.
* **Netplay for Game Boy games (same ROM).** Every client simulates both consoles, linked by mGBA's own `GBSIOLockstep`; only inputs,
  frame numbers and hashes cross the network (adaptive input delay, desync check and resync, shared virtual clock), exactly like GBA.
* **Different ROMs (e.g. Pokemon Red with Blue): classic Game Boy link**, a byte-by-byte link driver: the console that starts a transfer
  (SC = 0x81) waits for the other console's byte (one round trip per byte), the other console answers on arrival (answering 0xFF if its
  game has not armed the serial port within 8 frames, and when both consoles clock at once). Clearly warned as slow; measured with real
  Pokemon Red dumps: reaches the Trade Center at 0 ms and from 33 ms up to 400 ms one way (players starting the conversation at
  different moments). Mixed Game Boy / Game Boy Color pairs work the same way.
* **Native API 5** (older libraries keep working: an API 4 library plays Game Boy games single player and the mod says "Game Boy link
  needs a newer emulator library"): `gba_group_*` and `gba_link_*` now accept Game Boy cores (2 consoles, one platform per group),
  state trailer for Game Boy groups, a fix so a HALTed console cannot spin forever when the lockstep puts it to sleep.
* **Protocol still 4, additive only**: `hello` caps get `CAP_GAMEBOY_LINK`, an optional `cable_systems` packet (only sent to clients that
  registered it) tells the HUD what each cabled player runs; the server leaves a Game Boy game of a client without the capability out of
  the link with a message. No registered items or blocks.
* **Tests:** native `gblink` (lockstep and classic byte exchange, master role by whoever clocks first, CGB fast clock, collisions, state
  reload equivalence of the group trailer, dissolve, latency, unplug, idle partner) and `determinism_gblink` (pinned CRCs, compared by CI
  on all platforms through the Java hash check), Java `GameBoyLinkTest`, `GameBoyNetplayTest` (two clients with a fake relay, desync
  injection and resync, leaving), `LinkRulesTest` and new `EffectiveLinkTest` cases. ROMs: `native/tests/make_gb_link_rom.py`.
* **Dev tools:** `gb_pair_harness` (two real cartridges linked in one process, scripted keys, screenshots) and `gb_harness` state / poke
  options; dev autotest commands `gbpoke` / `gblog`.

## 0.5.0

* **Configurable controls.** A new **Controls** button (GBA screen buttons, CRT Options panel) opens a key map screen listing A, B, L, R,
  Start, Select, the D-pad, fast-forward, save state, load state and power off with their keys: click a button, press the new key (`Esc`
  cancels, right-click clears), a key another button has is swapped over to it, `Esc` / the volume keys / the CRT menu keys are
  reserved, **Reset to defaults**, **Done**. It opens while playing (the game keeps running) and the new keys apply at once. Stored per
  player in `gbalink/controls.properties` (a missing, damaged or inconsistent file means the defaults; the keypad-Enter alias for
  Start and the Tab alias for fast-forward stay until that button is rebound). Button labels, the power switch hover text and the CRT hint
  lines show the current keys. Game Boy / Game Boy Color games use the same map. Unit tests: `ControlMapTest`.
* **Game Boy and Game Boy Color games.** The Game Boy Advance, the Game Pak, the Blank Pak and the CRT TV now also play `.gb` and
  `.gbc` ROMs (single player; the link cable for Game Boy games comes in a later version). No new items: a Game Pak's tooltip names
  the system (Game Boy / Game Boy Color / Game Boy Advance) and its title and code come from the ROM header. The native library
  (**API version 4**, mGBA 0.10.5's GB/GBC core, +170 KB) detects the system from the ROM header (not the file name), maps the
  GBA-style keys to A, B, Select, Start and the D-pad (L / R ignored), resamples audio to the usual 32768 Hz, and supports battery saves
  (`.sav`, including the MBC3 clock footer of Pokémon Gold / Silver / Crystal), save states, reset and the virtual RTC. Monochrome games
  use a classic green palette, Game Boy Color games mGBA's normal colours (no colour correction), Super Game Boy borders are off.
* **GBA screen:** the bezel is unchanged; the Game Boy picture is drawn at its own 10:9 aspect, as large as fits with whole-number
  scaling, centred in the dark screen well, with a "GB" / "GBC" tag next to the title; the L / R shoulder buttons are hidden for those games.
* **CRT TV and spectators:** the picture is letterboxed (centred 1:1, black borders) into the existing 240x160 canvas, so the stream
  codec, the decoders and every viewer are unchanged. Sound is unchanged.
* **ROM library and burn screen:** scans `.gba`, `.gb` and `.gbc`; reads the right header for each (a Game Boy title / code, the colour
  flag and the header checksum; the GBA title and code), shows a system tag (`[GBA]`, `[GB]`, `[GBC]`) and the ROM ID, and skips invalid
  files with a clear reason shown in the list (Game Boy ROMs up to 8 MiB, GBA up to 32 MiB).
* **Saves:** `.sav` export / import accept Game Boy save sizes (512 B, 2, 8, 32, 64, 128 KiB plus an optional clock footer); suspend
  files record the platform (format version 2; version 1 files still read as GBA).
* **Server / protocol (still protocol 4, old clients keep working):** `burn` gets an optional appended `system` (sent only for Game Boy
  ROMs), `hello` caps get `CAP_GAMEBOY`, a pak item gets an optional `system` tag. A Game Boy session is left out of the link:
  it is unplugged, never routes link data and never starts netplay, other players keep linking; the client says "Link cable for Game Boy
  games isn't supported yet". A client whose native library is older than API 4 says to update the mod.
* **Tests:** native `gb` (boot, picture, palette, audio and tone, keys, save + clock footer round trips, state replay, reset, virtual RTC,
  platform switching, refusal of link / group calls) and `determinism_gb` (checked on all four platforms by the CI hash job), ROMs generated by
  `native/tests/make_gb_rom.py` (hand assembled, no downloads); Java `RomHeaderTest`, `GameBoyCoreTest`, `PakDataTest` and new cases in
  `SaveRulesTest` / `EffectiveLinkTest`; dev autotests `gb`, `gblinkA` / `gblinkB`; manual harness `gb_harness` for real cartridge dumps.

## 0.4.3

* **Confirm before leaving a link.** While you are linked (netplay or classic, or still connecting), leaving the GBA or
  the CRT ends the link for everyone and Pokemon shows "Communication error" on both sides. Esc, the Close / Leave
  buttons, the power switch and the `P` key now first show "You're linked with X. Leaving now disconnects the link cable
  and the game will show a communication error. Leave anyway?" with **Stay** (default; Esc or Enter in the popup also
  stay) and **Leave**. The emulator and the link keep running while it is shown (nothing is paused), GBA keys are ignored,
  and on the CRT the HUD stays hidden. Not linked: no popup, as before. Disconnecting from the server or quitting
  Minecraft cannot be prompted.
* **Minecraft volume is restored when you close.** The MC wheel still changes Minecraft's master volume live, but only
  for the time you play: the volume is snapshotted when the GBA / CRT screen opens and put back (and `options.txt`
  saved) when it closes by any path (Esc, Close, power off, leave, disconnect, quitting). The changed volume is not
  written to `options.txt` while playing, so a crash leaves the original. Sub-screens (import picker, confirmations,
  the CRT Options panel) do not count as closing. The GBA wheel stays persistent. Hover text: "MC 40% (while playing)".
* New unit tests (`LeaveAndRestoreTest`); the `lvA`/`lvB` dev autotest scenarios cover both in game.

## 0.4.2

* **Volume wheels** on the GBA bezel: **GBA** volume (game sound, saved per player in
  `config/gbalink-client.properties`, independent of Minecraft's master volume) and **MC** volume (Minecraft's master
  volume). Drag, scroll, or use `-`/`=` (GBA) and `[`/`]` (MC).
* **CRT Options panel** (`O` or the Options button): the same controls as the GBA screen (save/load state, export/import
  .sav, power switch, both volume wheels, Unlink, Leave). Hide it for the clean view.
* **CRT sound for everyone nearby**: players with the mod hear a running TV in the world (full volume up to 4 blocks,
  fading to silent at `crt_audio_distance`, default 24, with stereo pan), not only the 10 video viewers. The TV volume is
  the playing player's GBA wheel, so the player and the audience hear the same level.
* **Link status at a glance**: a corner indicator "Linked to X" (with the link state) whenever you are cabled, an
  actionbar message when you link or unlink, and **Unlink** on the GBA screen, in the CRT panel and on the `U` key (works
  anywhere; rebindable). Your partner sees "X unplugged the link cable."
* Fix: CRT TV audio never played on macOS (a second sound line blocked); one shared line is used now.
* **Fix: Pokemon Emerald "Communication error" in the Cable Club over netplay.** Netplay now links the simulated consoles
  with mGBA's own multiplayer lockstep (`GBASIOLockstep`, the code behind mGBA's multiplayer windows) instead of the
  0.4.0/0.4.1 hand-written link driver. A new native API 3 (`gba_group_create/add/run_frame/destroy`) steps all consoles on
  one thread in a fixed order, so netplay stays bit-deterministic (CI hash check on every platform). Verified with a real
  Emerald ROM: with the old driver both consoles end on "Communication error"; with lockstep both walk into the Trade Center.
* Netplay between 0.4.2 and older clients is not possible (different simulation): they fall back to the classic link
  (`CAP_NETPLAY_LOCKSTEP`). Classic (different ROMs) mode is unchanged.
* Save states of consoles in a lockstep group carry the lockstep state (128 byte trailer); mGBA's own states do not store
  write-only registers (SIOMLT_SEND) or the live SIOCNT/RCNT, which the trailer now carries.
* New tests: `group` (Normal/Multiplayer 2 to 4 consoles, a Gen 3 style timer-IRQ link ROM, pokesim, state reload equivalence),
  `emerald_harness` (manual, real ROM, never committed). `determinism` and `NetplayHashCheck` use groups.

## 0.4.1

* When a link falls back to the classic mode (players have different ROMs), a clear message says so:
  "Different games: slow link, may fail over the internet. Use the same ROM for full speed." The status line reads
  `Link (different games — slow, may fail online)`.
* **ROM ID**: Game Pak tooltips (and a GBA with a pak inserted) show `ROM ID: xxxxxxxx`, the first 8 hex digits of the
  ROM's SHA-1, and the burn picker lists it next to each game. Compare ROM IDs with your friends before linking: the same
  ROM ID means full-speed netplay.

## 0.4.0

* **Import .sav in the emulator** (replaces "Burn with existing save..."). There is an **Import .sav** button next to
  **Export .sav** on the GBA screen, and on the CRT the `I` key (`E` exports). Pick a file from `gbalink/imports/`
  (the picker has an **Open imports folder** button), confirm "Replace this cartridge's save with ...?", and the game
  restarts from the imported save with an "Imported <file>" message. Your current save is exported to
  `gbalink/exports/` (`...-before-import.sav`) first, a suspended game for that pak is discarded, and the server keeps
  the previous save as `<pak id>.sav.bak1` to `.bak3`. The server only accepts an import from the player who has that
  cartridge open, and still validates the save size. The burn picker no longer has the import option.
* **CRT: clean view.** While you play on a CRT the crosshair, held item and the whole HUD are hidden (like F1); your
  previous state is restored exactly when you leave. Only a small hint line stays. Nothing is written to `options.txt`.
* **Fix: audio flickers and clicks while paused.** The emulator and the CRT spectator audio lines no longer replay stale
  buffer data when nothing is playing (Minecraft pause menu, screen closed or suspended, leaving the CRT, power off):
  audio fades out over about 10 ms, the line is fed silence, and it fades back in on resume. Covered by a unit test
  that captures what is written to the device.
* The GBA screen buttons are `Save (F5)`, `Load (F8)`, `Export .sav`, `Import .sav`, `Close (Esc)`.
* All `gbalink/` folders (`exports`, `saves`, `states`, `suspend`, `natives`) are now created on the first launch.
* **README rewritten:** a release-files table (which file is for whom), separate server and client install
  sections, a folders section (per launcher), and updated play, import/export and CRT controls.
* **Netplay link: full speed over the internet.** When every linked player has a pak with the same game, the link no
  longer waits for the network on every transfer. Each client runs all linked consoles itself, connected with zero
  latency, and only controller inputs cross the network (with a small automatic input delay of 2 to 10 frames that
  adapts to your ping). Pokemon trading and battling no longer crawl at 100 ms ping; the old link needed a round trip per
  transfer (17 fps at 50 ms, 9 fps at 100 ms). Different games keep working with the old lockstep link, now called
  "classic link" (shown as "Link (different games - slow mode)"). Details: `docs/netplay.md`.
  * The status line reads "Link: P1 of 2 - netplay - delay 3f - ping 48 ms"; the line below shows fps, stalls/s, delay and
    desyncs. Clients compare a hash of all consoles every second; a mismatch is logged to `gbalink/link.log` and
    repaired automatically (player 0's consoles are sent to everybody).
  * Fast-forward, save/load state and import are off while linked; closing the GBA, breaking the cable, walking away or
    disconnecting ends the link for everybody. The remaining players restart it; a lone player continues seamlessly from
    the state of their console. Only your own console's save is uploaded.
  * Native library API version 2: `gba_set_rtc` gives every client the same cartridge clock (virtual, UTC, advancing with
    emulated time); loading a state now resets the link bookkeeping to fixed values; `gba_save_state` zeroes its buffer.
    Version 1 libraries still load but cannot do netplay.
  * CI runs a cross-platform determinism check (Linux, Windows, macOS arm64 and x86_64 must produce identical hashes).
  * Network protocol 4 also gains `netplay_start`, `netplay_input`, `netplay_hash`, `netplay_snapshot`,
    `netplay_restart`, a `mode` field in `link_state` and a capabilities field in `hello` (see SPEC.md).
* Network protocol 4: `import_chunk` is replaced by `import_save` {pakId, offset, total, bytes}; mismatched
  client/server versions are told to update the mod.

## 0.3.0

* **CRT TV.** A new craftable, placeable item (`/gba give crt`): a 90s-style wooden TV cabinet, 2 blocks wide and 2
  high, with a console, adapter and controller built in. Place it on a block, insert a Game Pak (sneak + right-click),
  right-click to play on the TV in the world. Esc suspends exactly like the GBA; saves, revisions, suspend and link
  cable work the same. Punch it to pick it up with the pak. It is made of ordinary display/interaction entities, so it
  survives restarts and vanilla clients still join (with the optional resource pack they see the model too).
* **Spectators.** Modded players within `crt_view_distance` (48) blocks watch the game live on the TV at 30 fps with
  low-quality sound (about 8 KB/s) relayed by the server (nearest first, `crt_max_viewers` = 10, "Viewer limit
  reached" overlay for the rest). Lossless palette/tile video codec (about 4 to 25 KB/s typical, 285 KB/s worst case),
  16 kHz IMA-ADPCM audio with distance attenuation and stereo pan. No input replay.
* New server settings file `config/gbalink.properties` (`crt_max_viewers`, `crt_view_distance`).
* Network protocol 3 (new `crt_open`, `crt_stream`, `crt_view`, `crt_close`, `crt_keyframe_request`,
  `crt_need_keyframe`); mismatched client/server versions are told to update the mod.
* The GBA key handling is shared between the GBA screen and the CRT control screen.

## 0.2.0

* **Suspend on close.** Closing the GBA screen suspends the game (battery save uploaded, core state saved to
  `gbalink/suspend/<pak id>.state`); reopening the pak resumes it exactly, across game restarts.
* **Power switch** on the bezel (with a power LED) and `P` key: real power off, deletes the suspend state, next
  open cold-boots. "Resumed" / "Powered on" messages.
* **Consistency guard.** The server reports a save revision (SHA-256 of the save) when a pak is opened and
  acknowledges every upload; a suspend state is only resumed if it matches the server's current save,
  otherwise it is discarded with "Save changed elsewhere - started fresh from the latest save".
* **Local save mirror:** every successful upload is also written to `gbalink/saves/<pak id>.sav`.
* **Export .sav** button (`gbalink/exports/`) and **Burn with existing save...** import from `gbalink/imports/`
  (size-validated on client and server; the server accepts it only as the first save of a freshly burned pak).
* **ROMs folder** is created on first start with a `README.txt`; **Open ROMs folder** button in the burn screen.
* **Save states are per pak** (`gbalink/states/<pak id>.state`); old ROM-checksum states are copied on first use.
* **Fast-forward on Space** (hold), Tab still works. Space no longer activates screen buttons.
* Network protocol 2 (new `welcome`, `save_ack`, `import_chunk`; `open` carries the save revision). Mismatched
  client/server versions tell the player to update the mod instead of failing.
* Fixes: closing within a second of the previous autosave no longer drops the final save.

## 0.1.0

* First public release: mGBA-based GBA emulator screen, Game Pak burning, server-side saves per pak, link cable
  for 2 to 4 players, save states (F5 / F8), fast-forward (Tab), crafting recipes built into the mod.
