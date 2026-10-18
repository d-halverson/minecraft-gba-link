# Netplay link (0.4.0, lockstep since 0.4.2)

Linked games run at full speed over the internet when everybody has a Game Pak with the **same game** (GBA, Game Boy and Game Boy Color alike).

## What you see

* Link two or more players with a cable, then each opens their GBA (or a CRT TV). While the others connect you see
  "Connecting link...". Then the status line under the GBA reads, for example:
  `Link: P1 of 2 · netplay · delay 3f · ping 48 ms`
  and the line below it: `Netplay: 59.8 fps · stalls 0.0/s · delay 3f · desyncs 0`.
* **delay** is the input delay in frames (1 frame = 16.7 ms). It adapts to your connection (2 to 10 frames) and is the same
  for everybody. At 50 ms ping it is normally 2 or 3 frames, so button presses feel about 40 to 50 ms late, in exchange for
  a game that never waits for the network.
* If the players have **different games** (different ROMs) the old "classic link" is used and the status line says
  `Link (different games - slow mode)`. It needs a network round trip per transfer, so high ping makes it crawl.
* Fast-forward, Save/Load state and Import .sav are switched off while linked in netplay (a message tells you). Closing the GBA,
  breaking the cable, walking out of range or disconnecting ends the link for everybody; the others reconnect automatically
  and a lone player simply continues from where their game was.

## How it works (short)

Every client simulates every linked console on one thread, joined by mGBA's own multiplayer link code (the same code mGBA's multiplayer windows use, which Pokemon Emerald's Cable Club accepts), stepped in a fixed order so all clients stay identical. Only the controller buttons are sent
over the network (a few bytes per frame). A frame only runs when everybody's buttons for it are known. Because the
emulator is deterministic, all clients stay identical. When a client starts, everybody sends their console's state so all
clients start from the same point (a few hundred KB, compressed), and the cartridge clock (Pokemon Emerald has one) is
virtual and the same for everyone while linked.

Every second the clients compare a checksum of all consoles. If they ever differ (a "desync", for example because a
friend runs a different build) it is written to `.minecraft/gbalink/link.log`, a short message appears, and player 1's
consoles are sent to everyone to repair it.

## Limits

* Game Boy / Game Boy Color: exactly 2 players (a Game Boy cable joins two consoles), linked by mGBA's `GBSIOLockstep`; a Game Boy
  game and a GBA game never link. Different Game Boy ROMs use a byte-by-byte classic link (one network round trip per byte).
* Up to 4 players (GBA). Each client emulates all of them: 4 players cost about 4x the CPU of one (about 1 ms per frame on a laptop
  for a light game).
* Only your own console's save is uploaded to the server.
* The cartridge clock shows the server's local time while linked (it is virtual and advances with emulated time).
* Netplay needs the native library of this version (API 3; API 5 for Game Boy games); the mod ships it. Players on 0.4.1 or older cannot join netplay with 0.4.2 players (the link is then classic).
* mGBA's link code only models Normal-mode (Game Boy style) cables loosely: the master reads 0xFFFFFFFF back. Multiplayer mode (Pokemon, most GBA games) is complete.
* `-Dgbalink.relaydelay=25` on a dev server adds 25 ms to every relayed packet, to test with a bad connection.
