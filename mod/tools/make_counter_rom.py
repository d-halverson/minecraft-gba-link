#!/usr/bin/env python3
"""Builds counter.gba, a tiny homebrew ROM used by the in-game QoL autotest (suspend/resume, power switch, import).

Every frame (at VBlank) it
  * increments the u32 at EWRAM 0x02000000 (frame counter) and mirrors it into the backdrop colour,
  * copies SRAM bytes 0..3 to EWRAM bytes 4..7 (shows which save the cartridge started with),
  * writes the low byte of the counter to SRAM byte 8 (so the battery save keeps changing).
The ROM contains the "SRAM_V113" marker so the emulator detects 32 KiB of SRAM.

usage: make_counter_rom.py out.gba
"""
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "native", "tests"))
from make_link_rom import Asm  # noqa: E402


def counter_rom():
    a = Asm()
    a.label("main")
    a.ldr_const("r5", 0x04000000)
    a.ldr_const("r6", 0x02000000)
    a.ldr_const("r8", 0x0E000000)
    a.ldr_const("r9", 0x05000000)
    a.mov("r1", 0)
    a.strh("r1", "r5", 0)                    # DISPCNT = 0: backdrop only
    a.mov("r4", 0)
    a.label("wait_vb")
    a.ldrh("r1", "r5", 6)
    a.cmp("r1", 160)
    a.bne("wait_vb")
    a.add("r4", "r4", 1)
    a.str("r4", "r6", 0)
    a.lsl("r1", "r4", 3)
    a.strh("r1", "r9", 0)                    # backdrop colour follows the counter
    for i in range(4):
        a._ldst(1, 1, "r1", "r8", i)         # ldrb r1, [sram + i]
        a._ldst(0, 1, "r1", "r6", 4 + i)     # strb r1, [ewram + 4 + i]
    a.and_("r1", "r4", 0xFF)
    a._ldst(0, 1, "r1", "r8", 8)             # sram[8] = counter & 0xFF
    a.label("wait_vb_end")
    a.ldrh("r1", "r5", 6)
    a.cmp("r1", 160)
    a.beq("wait_vb_end")
    a.b("wait_vb")
    return a


def build(path):
    asm = counter_rom()
    code_off = 0xC0
    body = asm.assemble(code_off)
    rom = bytearray(0x100 + len(body))
    off = (code_off + asm.labels["main"] * 4 - 8) >> 2
    struct.pack_into("<I", rom, 0, 0xEA000000 | (off & 0xFFFFFF))
    rom[0xA0:0xAC] = b"COUNTER".ljust(12, b"\0")
    rom[0xAC:0xB0] = b"CNTR"
    rom[0xB0:0xB2] = b"01"
    rom[0xB2] = 0x96
    chk = 0
    for b in rom[0xA0:0xBD]:
        chk = (chk - b) & 0xFF
    rom[0xBD] = (chk - 0x19) & 0xFF
    rom[code_off:code_off + len(body)] = body
    rom += b"SRAM_V113\0"
    rom += b"\xFF" * (-len(rom) % 0x200)
    with open(path, "wb") as f:
        f.write(rom)
    print("wrote", path, len(rom), "bytes")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "counter.gba")
