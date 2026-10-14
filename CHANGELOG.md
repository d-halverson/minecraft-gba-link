# Changelog

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
