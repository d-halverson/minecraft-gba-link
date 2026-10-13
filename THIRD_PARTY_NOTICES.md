# Third-party notices

GBA Link itself is MIT licensed (see `LICENSE`). It includes or builds against the following
third-party software.

## mGBA (MPL-2.0)

The emulator core is [mGBA](https://mgba.io/) 0.10.5, Copyright (c) 2013-2022 Jeffrey Pfau and
contributors, licensed under the [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/).

* Source code: <https://github.com/mgba-emu/mgba> (tag `0.10.5`). The exact revision used is pinned as a git
  submodule at `native/third_party/mgba`, so the complete corresponding source of the mGBA code in the
  binaries is part of this repository (`git submodule update --init`).
* License text: `native/third_party/mgba/LICENSE` (after checking out the submodule) and
  <https://github.com/mgba-emu/mgba/blob/0.10.5/LICENSE>.
* **mGBA is used unmodified.** No file of the mGBA tree is changed; GBA Link only adds its own wrapper
  (`native/src/gbacore.c`, MIT) and configures mGBA's build through CMake options in
  `native/CMakeLists.txt` (GBA core only, no frontends or optional dependencies, plus the compiler flag
  `-fwrapv`).
* The release binaries (`libgbacore.dylib`, `gbacore.dll`, `libgbacore.so`, embedded in the mod jar under
  `natives/`) statically link mGBA. Under the MPL the covered mGBA files remain under the MPL; the
  wrapper and the rest of GBA Link are separate files under the MIT license. You can rebuild the
  libraries from this repository with `native/build_target.sh` (see `native/README.md`).

## jsmolka/gba-tests (MIT)

The test ROMs in [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) (Copyright (c) 2019 Julian
Smolka, MIT license) are used only by the native unit tests (git submodule `native/third_party/gba-tests`).
They are not part of any release artifact.

## Minecraft, Fabric

The mod depends on [Fabric Loader](https://github.com/FabricMC/fabric-loader) and
[Fabric API](https://github.com/FabricMC/fabric) (Apache-2.0 / MIT), which are not bundled in the jar.
Minecraft is a trademark of Mojang AB / Microsoft. This project is not an official Minecraft product and
is not affiliated with Mojang or Microsoft.

## Game Boy Advance ROMs

No game ROMs or BIOS images are included or downloaded. Every user must supply their own legally
obtained ROM files. "Game Boy Advance" is a trademark of Nintendo; this project is not affiliated with
or endorsed by Nintendo.
