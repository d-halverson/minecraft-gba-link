# Changelog

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
