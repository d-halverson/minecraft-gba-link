#!/usr/bin/env python3
"""Generates the 16x16 item textures and model/item-definition JSON for gbalink. Stdlib only.
Run from anywhere: writes into ../src/client/resources/assets/gbalink/."""
import json
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "src", "client", "resources", "assets", "gbalink")


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, 255)


class Canvas:
    def __init__(self, w=16, h=16):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = rgb(c) if isinstance(c, int) else c

    def rect(self, x0, y0, x1, y1, c):  # inclusive
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def line(self, pts, c):
        for x, y in pts:
            self.set(x, y, c)

    def png(self):
        raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in self.px)

        def chunk(t, d):
            c = struct.pack(">I", len(d)) + t + d
            return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9))
                + chunk(b"IEND", b""))


OUT, BODY, HI, SH = 0x1E1840, 0x4B3C94, 0x7466BD, 0x32286E


def gba():
    c = Canvas()
    c.rect(0, 3, 15, 12, OUT)
    for p in [(0, 3), (15, 3), (0, 12), (15, 12)]:
        c.set(*p, (0, 0, 0, 0))
    c.rect(1, 4, 14, 11, BODY)
    c.rect(1, 4, 14, 4, HI)
    c.rect(1, 11, 14, 11, SH)
    c.rect(4, 5, 11, 10, 0x14121F)        # screen well
    c.rect(5, 6, 10, 9, 0x8FD3C0)         # lit screen
    c.rect(5, 6, 10, 6, 0xB8EBDD)
    c.rect(2, 7, 3, 7, 0x231C4A)           # d-pad
    c.rect(2, 6, 2, 8, 0x231C4A)
    c.set(2, 7, 0x231C4A)
    c.set(12, 6, 0xC2457E)                 # A / B
    c.set(13, 8, 0xC2457E)
    c.set(12, 9, 0x231C4A)
    return c


def cart(pak):
    c = Canvas()
    c.rect(3, 1, 12, 14, OUT)
    c.set(3, 1, (0, 0, 0, 0)); c.set(12, 1, (0, 0, 0, 0))
    c.rect(4, 2, 11, 13, 0x8E8E9A)
    c.rect(4, 2, 11, 2, 0xB4B4C0)
    c.rect(11, 2, 11, 13, 0x6E6E7A)
    c.rect(5, 11, 10, 13, 0x4A4A55)       # lower shell / connector area
    c.rect(6, 13, 9, 13, 0xD9B44A)        # gold contacts
    c.rect(5, 4, 10, 9, 0xC9C9D4 if not pak else 0x5B3FB5)  # label
    if pak:
        c.rect(5, 4, 10, 4, 0x7A5FD6)
        c.rect(6, 5, 9, 7, 0xF2E9FF)      # artwork window
        c.set(7, 6, 0xE0457B); c.set(8, 6, 0xF2C94C); c.set(8, 7, 0x4C9BE0)
        c.line([(6, 8), (7, 8), (9, 8)], 0xD8CCFF)
    else:
        c.line([(6, 6), (7, 6), (8, 6), (9, 6)], 0xA8A8B4)
    c.rect(7, 10, 8, 10, 0x4A4A55)
    return c


def cable():
    c = Canvas()
    wire, wire2 = 0x8A6BE0, 0x5B3FB5
    for x, y in [(4, 11), (4, 10), (5, 9), (5, 8), (6, 8), (7, 7), (8, 7), (9, 6), (10, 6), (10, 5), (11, 5)]:
        c.set(x, y, wire)
    for x, y in [(5, 11), (5, 10), (6, 9), (6, 10), (7, 8), (8, 8), (9, 7), (10, 7), (11, 6)]:
        c.set(x, y, wire2)
    # plug (bottom-left)
    c.rect(1, 11, 4, 14, OUT)
    c.rect(2, 12, 3, 13, 0x9A9AA8)
    c.set(1, 14, (0, 0, 0, 0))
    c.rect(1, 10, 2, 10, 0x4A4A55)
    # plug (top-right)
    c.rect(11, 1, 14, 4, OUT)
    c.rect(12, 2, 13, 3, 0x9A9AA8)
    c.set(14, 1, (0, 0, 0, 0))
    c.rect(13, 5, 14, 5, 0x4A4A55)
    return c


def write(path, data, mode="wb"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(data)


def main():
    textures = {"gba": gba(), "blank_pak": cart(False), "pak": cart(True), "cable": cable()}
    for name, canvas in textures.items():
        write(os.path.join(ROOT, "textures", "item", name + ".png"), canvas.png())
        write(os.path.join(ROOT, "models", "item", name + ".json"),
              json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": "gbalink:item/" + name}}, indent=2) + "\n", "w")
        write(os.path.join(ROOT, "items", name + ".json"),
              json.dumps({"model": {"type": "minecraft:model", "model": "gbalink:item/" + name}}, indent=2) + "\n", "w")
    print("wrote", ", ".join(textures))


if __name__ == "__main__":
    main()
