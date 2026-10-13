# Changelog

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
