# GBA Link

Play Game Boy Advance games inside Minecraft, and link up with friends using a Link Cable.
A Fabric mod for **Minecraft 26.3**, built on the [mGBA](https://mgba.io/) emulator core.

![The in-game Game Boy Advance screen](docs/screenshot.png)

## What it is

* **A playable GBA in your inventory.** Craft a Game Boy Advance, burn a game onto a Blank Game Pak,
  insert it, and play in a GBA-shaped screen with sound.
* **Real link cable support.** Use a Link Cable on a friend and play multiplayer GBA games together
  (2 to 4 players), for example trading or battling.
* **Saves live on the server**, inside each Game Pak, so a pak keeps its progress when you hand it to a
  friend or log in from another computer. Closing the screen suspends your game exactly where it was; save states (F5 / F8), fast-forward
  (Space or Tab) and .sav export / import are built in.
* **Easy to host.** One mod jar (plus Fabric API) on the server. Recipes are built into the mod, no
  datapack needed. Players without the mod can still join and see the items; they just can't play.
* **Bring your own ROMs.** No games, BIOS files or downloads are included or provided by this project.
  You must supply ROM files you legally own. (mGBA's built-in replacement BIOS is used, so you don't
  need a BIOS file either.)

Works on Windows (x64), macOS (Intel and Apple Silicon) and Linux (x64).

## Server install

Requires Java 25 and a [Fabric](https://fabricmc.net/use/server/) server for Minecraft 26.3
(loader 0.19.5 or newer).

1. Set up a Fabric server for 26.3 and start it once.
2. Put these two files into the server's `mods/` folder:
   * [Fabric API](https://modrinth.com/mod/fabric-api) `0.162.0+26.3` (or newer for 26.3)
   * `gbalink-<version>.jar` from the [Releases](../../releases) page
3. Restart the server. That's all: the crafting recipes ship inside the jar.

**Optional: item textures for vanilla players.** Players who have the mod see the custom item
textures automatically. To give players *without* the mod nicer textures too, host
`gbalink-resourcepack-<version>.zip` (also on the Releases page) at a direct download URL and set it as the server resource
pack in `server.properties`:

```properties
resource-pack=https://example.com/gbalink-resourcepack-0.2.0.zip
resource-pack-sha1=<sha1 of the zip>
```

Get the checksum with `shasum -a 1 gbalink-resourcepack-0.2.0.zip` (macOS/Linux) or
`certutil -hashfile gbalink-resourcepack-0.2.0.zip SHA1` (Windows). Without the resource pack, vanilla
players simply see the plain base items (amethyst shard, iron nugget, copper ingot) with their custom names.

**Vanilla clients can still join.** The mod registers no blocks, items or other registry entries; the
items are ordinary vanilla items carrying custom data, and everything else uses optional custom packets.
Players without the mod can pick up and carry the items, and are told to install the mod when they try to
use one.

**Admin commands**

| Command | Who | What |
| --- | --- | --- |
| `/gba give <gba\|blank_pak\|cable> [player]` | operators | Hand out items |
| `/gba info` | everyone | Shows whether your client has the mod and your link status |
| `/gba unlink` | everyone | Removes your link cable connection |

Saves are stored in the world folder: `world/gbalink/saves/<pak id>.sav`. Back them up with your world.
Clients and server must run the same protocol: the 0.2.0 mod on one side and 0.1.0 on the other will not
play together; the player is told to update the mod.

## Client install

Players need the mod on their own computer to actually play. The game must be Minecraft **26.3** with
Fabric Loader **0.19.5** or newer.

### Option A: official Minecraft Launcher

1. Download the [Fabric installer](https://fabricmc.net/use/installer/), run it, pick **Minecraft 26.3**
   and **loader 0.19.5**, and click Install. This creates a "fabric-loader-26.3" profile.
2. Download **Fabric API** `0.162.0+26.3` (from [Modrinth](https://modrinth.com/mod/fabric-api/versions))
   and `gbalink-<version>.jar` (from [Releases](../../releases)).
3. Put both jars into your `mods` folder (create it if it doesn't exist):

   | OS | Folder |
   | --- | --- |
   | Windows | `%APPDATA%\.minecraft\mods` |
   | macOS | `~/Library/Application Support/minecraft/mods` |
   | Linux | `~/.minecraft/mods` |

4. Start the launcher, choose the **Fabric** profile, and play. Fabric API and Java 25 are required (the
   launcher downloads a suitable Java automatically).

### Option B: Prism Launcher (or the Modrinth App)

**Easy path:** download `gbalink-<version>.mrpack` from [Releases](../../releases) and import it
(Prism: *Add Instance, Import*, drop the file in; Modrinth App: *Add instance, From file*).
It sets up Minecraft 26.3, Fabric Loader 0.19.5, Fabric API and GBA Link in one go.

**Manual path:**

1. Create a new instance for Minecraft **26.3** and choose **Fabric** as the loader.
2. Open *Edit instance, Mods, Download mods*, search for **Fabric API** on Modrinth, and install the
   version for 26.3.
3. Click *Add file* (or open the mods folder) and add `gbalink-<version>.jar`.

## Adding games

Copy your own `.gba` files into the `gbalink/roms/` folder of your game directory. It is created the
first time you start Minecraft with the mod (with a short `README.txt` inside), and the burn screen has an
**Open ROMs folder** button that opens it in your file manager.

| Launcher | ROM folder |
| --- | --- |
| Windows | `%APPDATA%\.minecraft\gbalink\roms` |
| macOS | `~/Library/Application Support/minecraft/gbalink/roms` |
| Linux | `~/.minecraft/gbalink/roms` |
| Prism / Modrinth App | `<instance folder>/minecraft/gbalink/roms` (or `.minecraft`; in Prism right-click the instance, *Folder*) |

Games are matched by **SHA-1 of the ROM file**. When a Game Pak is burned, it remembers the checksum of
that ROM. Everyone who wants to play that pak must have the *exact same ROM file* (same dump, same
region, same version) in their folder. If the file doesn't match, the GBA tells you which game it is
missing. Subfolders (one level deep) are scanned too.

## How to play

**Crafting** (shaped recipes, items are placed as shown):

| Item | Recipe |
| --- | --- |
| Game Boy Advance | `A G A` / `I R I` |
| Blank Game Pak | `N R N` / `N I N` |
| Link Cable | `C A C` / `_ S _` |

A = amethyst shard, G = glass pane, I = iron ingot, R = redstone, N = iron nugget, C = copper ingot,
S = string, `_` = empty. (Operators can also use `/gba give`.)

**Burn a game.** Hold a Blank Game Pak and press **G** (rebindable under Controls, "GBA Link") or
right-click. Pick one of your ROMs from the list; the pak is now that game. Each burned pak has its own
save file on the server.

**Insert / eject.** Hold the GBA in your main hand and a Game Pak in your **off hand**, then
**sneak + right-click** to insert it. To eject, sneak + right-click with an empty off hand; the pak lands
back in your off hand.

**Play.** Right-click with the GBA (with a pak inserted) to open the console screen.

| GBA | Key |
| --- | --- |
| D-pad | Arrow keys |
| A / B | `X` / `Z` |
| L / R | `A` / `S` |
| Start / Select | `Enter` / `Backspace` |
| Fast-forward (hold) | `Space` (or `Tab`) |
| Save state / load state | `F5` / `F8` |
| Close (suspends the game) | `Esc` |
| Power off | `P`, or click the power switch on the top edge of the GBA |

**Suspend and power switch.** Closing the GBA screen (`Esc` or the Close button) does not turn the console
off: it uploads your battery save to the server and writes a *suspend state* to
`gbalink/suspend/<pak id>.state`. The next time you open that pak the game continues exactly where you left
it, even after restarting Minecraft (a "Resumed" message appears). Slide the **power switch** (top edge, next
to the green power LED) or press `P` for a real power off: the save is flushed, the suspend state is deleted,
and the next open boots the game from scratch ("Powered on").

The suspend state never overrides a newer save. Every save on the server has a revision (the SHA-256 of the
save file), and a suspend state remembers the revision it was based on. If the pak's save changed somewhere
else in the meantime (you gave the pak to a friend who played it, or you played it on another computer), the
suspend state is thrown away and the game starts fresh from the latest save, with the message
"Save changed elsewhere - started fresh from the latest save".

Your in-game save (battery save) is uploaded to the server as you play and stored per pak. Every
successful upload is also copied to `gbalink/saves/<pak id>.sav` on your computer as a personal backup.
Save states (F5 / F8) are an extra, local snapshot kept per pak on your own computer
(`gbalink/states/<pak id>.state`; a state saved by 0.1.0 under the ROM's checksum is copied over on first use).

**Export a save.** The **Export .sav** button on the GBA screen writes the current battery save to
`gbalink/exports/<game>-<date>.sav`. These are the emulator's battery files, the same format other emulators
(mGBA and others) use: 512 B or 8 KiB (EEPROM), 32 KiB (SRAM), 64 or 128 KiB (Flash). For games with a
real-time clock mGBA appends a 16-byte clock footer; mGBA reads it, other emulators ignore or trim it.

**Import a save.** Put a `.sav` file into `gbalink/imports/`, hold a Blank Game Pak, and in the burn screen
click **Burn with existing save...**, choose the file, then choose the game. The new pak starts with that save.
Only the standard sizes (512 B, 8, 32, 64, 128 KiB, each optionally plus 16 bytes of clock footer, never more
than 512 KiB) are accepted, and the server only takes an import as the very first save of a pak you just burned
(within two minutes).

**Folders** (all inside `gbalink/` in your game directory):

| Folder | Contents |
| --- | --- |
| `roms/` | your `.gba` games (with a `README.txt`) |
| `imports/` | `.sav` files you want to burn onto a new pak |
| `exports/` | saves written by Export .sav |
| `saves/` | automatic local copy of each pak's last uploaded save |
| `suspend/` | suspended games, one per pak |
| `states/` | F5 save states, one per pak |

## Link cable

Hold a Link Cable and **right-click another player** who is within **16 blocks** and also has the mod.
Up to **4 players** can share one cable room; use the cable on each additional friend. Then everyone
**opens their GBA** (with a link-capable game, such as a trading or multiplayer menu) and the games find
each other, just like real GBAs. You are disconnected if you wander out of range, or with `/gba unlink`.

Speed depends on your ping, honestly: every link transfer needs a network round trip, and the emulator
waits for it. On a LAN or a nearby server most games feel fine; over the internet, games that exchange
data every single frame (some multiplayer action games) run at roughly your round-trip rate.
Trading and turn-based battling work well even with higher ping.

## Supported platforms

| OS | CPU | |
| --- | --- | --- |
| Windows 10+ | x64 | yes |
| macOS 11+ | Intel and Apple Silicon | yes |
| Linux (glibc 2.17+) | x64 | yes |

Linux ARM and Windows ARM are not supported. On an unsupported platform the mod still loads (and the
server side works); the GBA screen just says the emulator is unavailable.

## Building from source

You need Git, JDK 25, CMake 3.16+, a C compiler, Python 3, and [Zig](https://ziglang.org/) 0.17 (only to
build the Windows and Linux libraries).

```sh
git clone --recurse-submodules https://github.com/d-halverson/minecraft-gba-link.git
cd minecraft-gba-link

# 1. native emulator libraries -> dist/natives/<platform>/
#    (macOS builds both Intel and Apple Silicon; with zig installed it also builds Windows and Linux)
native/build_all.sh
# or just one platform:  native/build_target.sh macos-aarch64 --test

# 2. the mod (packs whatever is in dist/natives/ into the jar)
cd mod
./gradlew build
```

Results are in `mod/build/libs/`: `gbalink-<version>.jar` and `gbalink-resourcepack-<version>.zip`.
A jar only contains the natives you built, so build all four platforms for a release (the GitHub
Actions workflow in `.github/workflows/build.yml` does exactly this and attaches everything to a release
when you push a tag like `v0.2.0`). On Windows, build the natives with WSL or let CI do it. See
[`native/README.md`](native/README.md) for details and [`SPEC.md`](SPEC.md) for the design.

The tests (`./gradlew test`, `native/run_tests.sh`) use only small homebrew ROMs generated by
`native/tests/make_link_rom.py` and the MIT-licensed [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests)
submodule.

## Known limitations

* **Link speed over the internet** is bound by latency (see above).
* **No Wireless Adapter**, GBA Player, JOY bus or UART mode. Only the cable modes (Normal 8/32-bit and
  Multiplayer, 2 to 4 players) are emulated. Normal mode with 3 to 4 players is only approximate.
* **mGBA 0.10.5 accuracy:** very accurate for commercial games, but a few exotic hardware edge cases
  fail jsmolka's strictest tests (for example CMP with PC as destination, STM with the base register in
  the register list, unaligned halfword stores to SRAM). Games that depend on real BIOS timing may
  differ because the replacement (HLE) BIOS is used.
* Link state is not part of a save state; loading a state or resetting disconnects a transfer in flight.
* Everyone playing the same pak must have the identical ROM file.
* Early version (0.2.0): expect rough edges. Please report bugs with your OS, game and what happened.

## Credits and license

* Emulation by **[mGBA](https://mgba.io/)** by Jeffrey Pfau and contributors (MPL-2.0), statically linked
  and unmodified. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
* Test ROMs from [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) (MIT).
* Built with [Fabric](https://fabricmc.net/).
* GBA Link itself is [MIT licensed](LICENSE). "Game Boy Advance" is a trademark of Nintendo; Minecraft is
  a trademark of Mojang/Microsoft. This project is not affiliated with or endorsed by any of them.
