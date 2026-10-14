#!/usr/bin/env python3
"""Writes tone.gba: a 400-byte homebrew test ROM that plays a steady 440 Hz square wave on sound channel 1 (original
code, hand-assembled ARM). Used by the dev autotest to check that a CRT's audio reaches nearby players.
usage: make_tone_rom.py [out.gba]"""
import struct
import sys

out = sys.argv[1] if len(sys.argv) > 1 else "tone.gba"
hdr = bytearray(0xC0)
hdr[0:4] = struct.pack("<I", 0xEA00002E)                  # b 0xC0
logo = bytes.fromhex("24FFAE51699AA2213D84820A84E409AD11248B98C0817F21A352BE199309CE2010464A4AEC")  # not checked by emulators
hdr[4:4 + len(logo)] = logo
hdr[0xA0:0xAC] = b"GBA Tone".ljust(12, b"\0")
hdr[0xAC:0xB0] = b"1337"
hdr[0xB0:0xB2] = b"JS"
hdr[0xB2] = 0x96
hdr[0xBC] = 0
hdr[0xBD] = (-(sum(hdr[0xA0:0xBD]) + 0x19)) & 0xFF
code = [
    0xE3A00301,  # mov r0, #0x04000000
    0xE3A01080, 0xE1C018B4,  # SOUNDCNT_X = 0x80 (sound on)
    0xE3A01CFF, 0xE3811077, 0xE1C018B0,  # SOUNDCNT_L = 0xFF77 (channel 1..4 both sides, full volume)
    0xE3A01002, 0xE1C018B2,  # SOUNDCNT_H = 2 (PSG 100%)
    0xE3A01000, 0xE1C016B0,  # SOUND1CNT_L = 0 (no sweep)
    0xE3A01A0F, 0xE3811080, 0xE1C016B2,  # SOUND1CNT_H = 0xF080 (volume 15, duty 50%)
    0xE3A01C86, 0xE38110D6, 0xE1C016B4,  # SOUND1CNT_X = 0x86D6 (440 Hz, start)
    0xEAFFFFFE,  # b .
]
rom = bytes(hdr) + b"".join(struct.pack("<I", w) for w in code)
rom = rom.ljust(512, b"\0")
open(out, "wb").write(rom)
print("wrote", out, len(rom), "bytes")
