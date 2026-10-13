#!/usr/bin/env python3
"""Generates the CRT TV: a 128x128 texture atlas and the 3D item models (elements) for gbalink. Stdlib only.
Writes textures/item/crt.png, models/item/crt.json (inventory/hand) and crt_placed.json (item_display), and
items/crt.json + items/crt_placed.json. Run from anywhere. Geometry constants must match
com.drew.gba.crt.CrtGeometry (screen rectangle) and are checked at the end."""
import json
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "src", "client", "resources", "assets", "gbalink")
N = 512  # atlas size; texel density on detail faces is 4 px per model unit
S = N / 16.0  # atlas pixels per uv unit

# ---------------------------------------------------------------- atlas

atlas = [[(0, 0, 0, 0)] * N for _ in range(N)]
tiles = {}
_cursor = {"x": 0, "y": 0, "row": 0}


def rgb(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


def shade(c, d):
    r, g, b, a = c
    return (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), a)


def noise(x, y, seed=0):
    v = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)
    v = (v ^ (v >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((v >> 8) & 255) / 255.0


def alloc(name, w, h):
    if _cursor["x"] + w > N:
        _cursor["x"] = 0
        _cursor["y"] += _cursor["row"] + 1
        _cursor["row"] = 0
    x, y = _cursor["x"], _cursor["y"]
    _cursor["x"] += w + 1
    _cursor["row"] = max(_cursor["row"], h)
    assert y + h <= N, "atlas full"
    tiles[name] = (x, y, w, h)
    return x, y


class Tile:
    def __init__(self, name, w, h):
        self.x, self.y = alloc(name, w, h)
        self.w, self.h = w, h

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            atlas[self.y + y][self.x + x] = rgb(c) if isinstance(c, int) else c

    def get(self, x, y):
        return atlas[self.y + y][self.x + x]

    def rect(self, x0, y0, x1, y1, c):  # inclusive
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def fill(self, c, grain=0, seed=1):
        for y in range(self.h):
            for x in range(self.w):
                d = int((noise(x, y, seed) - 0.5) * 2 * grain) if grain else 0
                self.set(x, y, shade(rgb(c), d))

    def bevel(self, light, dark):
        for x in range(self.w):
            self.set(x, 0, light)
            self.set(x, self.h - 1, dark)
        for y in range(self.h):
            self.set(0, y, light)
            self.set(self.w - 1, y, dark)

    def disc(self, cx, cy, r, c):
        for y in range(self.h):
            for x in range(self.w):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.set(x, y, c)


SW = {}


def swatch(name, color):
    t = Tile("sw_" + name, 4, 4)
    t.fill(color)
    SW[name] = t


# solid colours (4x4 blocks so mip-mapping never bleeds neighbours)
for _n, _c in {
    "graphite": 0x3A3A42, "graphite_l": 0x55555E, "graphite_d": 0x25252B, "plinth": 0x17171C, "wheel": 0x0C0C0F,
    "hub": 0x7A7A86, "dark_in": 0x0F0F14, "wood": 0x8A5A34, "wood_d": 0x6E4526, "black": 0x08080C,
    "indigo": 0x4B3C94, "indigo_d": 0x352A70, "indigo_l": 0x7466BD, "violet": 0x5B43B8, "violet_l": 0x7A63D6,
    "adapter": 0x2A2A33, "grey": 0xB8B8C4, "grey_d": 0x6E6E7A, "green": 0x2FBF71, "red": 0xD13B4E,
    "yellow": 0xE6C84A, "cable": 0x1A1626, "led": 0x39D98A, "dpad": 0x1C1640, "pad_b": 0x6A52C8, "shelf_floor": 0x4A4A5E, "shelf_back": 0x34343F, "shelf_ceil": 0x22222B, "glow": 0xCFCFEE, "metal": 0xA8A8B6,
}.items():
    swatch(_n, _c)


def sw_uv(name):
    t = SW[name]
    return [(t.x + 1) / S, (t.y + 1) / S, (t.x + 3) / S, (t.y + 3) / S]


def tile_uv(name):
    x, y, w, h = tiles[name]
    return [x / S, y / S, (x + w) / S, (y + h) / S]


def uv(ref):
    if isinstance(ref, tuple):  # (tile, "rot180")
        u0, v0, u1, v1 = tile_uv(ref[0])
        return [u1, v1, u0, v0]
    return tile_uv(ref) if ref in tiles else sw_uv(ref)


# ---------------------------------------------------------------- detail tiles

def wood(t, vertical=True, base=0x8A5A34, seed=3):
    """Walnut veneer: long grain streaks with a few knots-ish darker lines."""
    for y in range(t.h):
        for x in range(t.w):
            along, across = (y, x) if vertical else (x, y)
            streak = noise(across // 1, seed, 7) - 0.5          # per-column tone
            wave = noise(across, along // 9, seed + 1) - 0.5      # slow variation along the grain
            fine = noise(x, y, seed + 2) - 0.5
            d = int(streak * 26 + wave * 14 + fine * 6)
            if noise(across, 0, seed + 5) > 0.86:                  # dark pore line
                d -= 20
            t.set(x, y, shade(rgb(base), d))


# cabinet side panel (16 deep x 28.6 high) and front edge strip, top
t = Tile("wood_side", 64, 114)
wood(t, True)
t.rect(0, 0, 63, 0, 0x6A4326)
t.rect(0, 113, 63, 113, 0x5A381F)
t = Tile("wood_edge", 8, 114)       # 2 x 28.6 front edge of the side panels
wood(t, True, 0x93613A, 9)
t.rect(0, 0, 0, 113, 0xA8764C)
t.rect(7, 0, 7, 113, 0x5A381F)
t = Tile("wood_top", 64, 64)
wood(t, False, 0x93613A, 11)
t = Tile("wood_trim", 128, 6)       # 32 x 1.2 front of the top trim
wood(t, False, 0x93613A, 13)
t.rect(0, 0, 127, 0, 0xB07C50)
t.rect(0, 5, 127, 5, 0x5A381F)

# glass: dark grey tube face (22.2 x 16.65 units) with a soft reflection
GW, GH = 89, 67
t = Tile("glass", GW, GH)
for y in range(GH):
    for x in range(GW):
        edge = min(x, GW - 1 - x, y, GH - 1 - y)
        c = rgb(0x1E2624 if edge > 5 else 0x121817)
        d = (x * 0.6 + y) - 34
        if -5 < d < 5 and x < 60 and y < 40:
            c = shade(c, 10 - int(abs(d) * 2))
        if x + y < 18:
            c = shade(c, 10)
        t.set(x, y, c)

# speaker strip under the screen (28 x 2.4 units = 112 x 10): two round grilles, small control panel in the middle
t = Tile("strip", 112, 10)
t.fill(0x303037, 2, 5)
t.rect(0, 0, 111, 0, 0x4E4E58)
t.rect(0, 9, 111, 9, 0x17171B)
for cx in (14, 98):
    t.disc(cx, 5, 4, 0x15151A)
    for yy in range(2, 9, 2):
        for xx in range(cx - 4, cx + 5, 2):
            if (xx - cx) ** 2 + (yy - 5) ** 2 <= 14:
                t.set(xx, yy, 0x3A3A42)
t.rect(44, 2, 67, 7, 0x15151A)
t.rect(44, 2, 67, 2, 0x2A2A32)
for bx in range(50, 66, 3):
    t.rect(bx, 5, bx + 1, 6, 0x6E6E7A)
t.rect(46, 3, 47, 4, 0x39D98A)        # power LED (green)
t.rect(49, 3, 50, 4, 0xD13B4E)        # standby LED (red)

# console front (11 x 5.5 units = 44 x 22)
CW, CH = 44, 22
t = Tile("console_front", CW, CH)
t.fill(0x4B3C94, 3, 11)
t.rect(0, 0, CW - 1, 1, 0x7466BD)
t.rect(0, CH - 2, CW - 1, CH - 1, 0x2A2060)
t.rect(0, 0, 0, CH - 1, 0x6458B0)
t.rect(CW - 1, 0, CW - 1, CH - 1, 0x2A2060)
t.rect(4, 3, 24, 12, 0x2A2060)         # disc lid
t.rect(5, 4, 23, 11, 0x5D4EB0)
t.rect(5, 4, 23, 4, 0x7466BD)
t.disc(14, 8, 3, 0x4B3C94)
t.disc(14, 8, 1, 0x2A2060)
t.disc(35, 6, 2, 0xB8B8C4)             # power
t.disc(35, 6, 1, 0x39D98A)
t.rect(30, 10, 38, 11, 0x2A2060)       # open
t.rect(31, 10, 37, 10, 0x7466BD)
for px in (4, 13, 22, 31):              # four controller ports; port 1 is where the cable plugs in
    t.rect(px, 15, px + 4, 19, 0x14102C)
    t.rect(px + 1, 16, px + 3, 18, 0x08060F)
    t.rect(px, 14, px + 4, 14, 0x6458B0)

# console side (9 deep x 5.5 = 36 x 22)
t = Tile("console_side", 36, 22)
t.fill(0x4B3C94, 3, 12)
t.rect(0, 0, 35, 1, 0x7466BD)
t.rect(0, 20, 35, 21, 0x2A2060)
for y in (7, 11, 15):
    t.rect(6, y, 29, y, 0x241A58)
    t.rect(6, y + 1, 29, y + 1, 0x5D4EB0)

# adapter front (14 x 1.4 = 56 x 6)
t = Tile("adapter_front", 56, 6)
t.fill(0x2A2A33, 2, 13)
t.rect(0, 0, 55, 0, 0x4A4A58)
t.rect(0, 5, 55, 5, 0x16161C)
t.rect(8, 2, 30, 3, 0x050508)           # cartridge slot
t.rect(40, 2, 42, 3, 0x39D98A)          # LED
t.rect(44, 2, 46, 3, 0x6458B0)

# controller top (11 x 7.25 units = 44 x 29), painted as the player sees it (cable edge on top)
PW, PH = 44, 29
K = PW / 36.0


def k(v):
    return int(round(v * K))


t = Tile("pad_top", PW, PH)
t.fill(0x5B43B8, 3, 14)
t.rect(0, 0, PW - 1, 0, 0x7A63D6)
t.rect(0, PH - 1, PW - 1, PH - 1, 0x3E2D8A)
t.disc(k(9), k(7), k(5), 0x3A2A80)      # analog stick well
t.disc(k(9), k(7), k(4), 0x4B3C94)
t.disc(k(27), k(9), k(6), 0x3A2A80)     # A ring
t.disc(k(27), k(9), k(5), 0x2FBF71)
t.disc(k(19), k(14), k(2), 0xD13B4E)    # B
t.disc(k(27), k(18), k(3), 0x3A2A80)    # C stick well
t.rect(k(15), k(4), k(20), k(5), 0x9A8AE0)  # start

# ---------------------------------------------------------------- elements

elements = []
XO = -16  # the 2-wide model is authored in x 0..32 and shifted so the entity (model x 8) is the viewer's left cell


def box(frm, to, faces=None, default="graphite", rot=None, comment=None):
    faces = faces or {}
    el = {"from": [round(frm[0] + XO, 4), round(frm[1], 4), round(frm[2], 4)],
          "to": [round(to[0] + XO, 4), round(to[1], 4), round(to[2], 4)], "faces": {}}
    if comment:
        el["name"] = comment
    for d in ("north", "south", "east", "west", "up", "down"):
        ref = faces.get(d, default)
        if ref is None:
            continue
        el["faces"][d] = {"uv": uv(ref), "texture": "#t"}
    if rot:
        r = dict(rot)
        r["origin"] = [r["origin"][0] + XO, r["origin"][1], r["origin"][2]]
        el["rotation"] = r
    elements.append(el)


# screen geometry (authored coordinates); the tube is 4:3, the GBA picture (3:2) fills its width
GX0, GX1, GY0, GY1 = 4.9, 27.1, 13.4, 30.05
SX0, SX1 = 5.2, 26.8
SY0 = (GY0 + GY1) / 2 - 21.6 / 3.0
SY1 = (GY0 + GY1) / 2 + 21.6 / 3.0
SZ0 = 1.6
SX0 += XO; SX1 += XO

# --- casters, plinth
for cx in (3.0, 27.0):
    for cz in (1.0, 12.6):
        box([cx, 0, cz], [cx + 2, 1.0, cz + 2.4], {"east": "hub", "west": "hub"}, "wheel", comment="caster")
box([0, 1.0, -1.6], [32, 2.0, 16], {"north": "plinth", "up": "plinth"}, "plinth", comment="plinth")
box([1, 1.0, -2.3], [31, 1.9, -1.6], default="plinth", comment="plinth lip")

# --- cabinet: side panels, top trim, divider, back
for x0, x1 in ((0, 2), (30, 32)):
    box([x0, 2.0, 0], [x1, 31, 16], {"north": "wood_edge", "east": "wood_side", "west": "wood_side", "up": "wood_top"}, "wood_d", comment="side panel")
box([0, 31, -0.8], [32, 32, 16], {"north": "wood_trim", "up": "wood_top", "east": "wood_trim", "west": "wood_trim"}, "wood_d", comment="top trim")
box([2, 2.0, 14], [30, 31, 16], default="dark_in", comment="back")
box([2, 2.0, 0], [30, 2.5, 14], {"up": "shelf_floor"}, "shelf_floor", comment="shelf floor")
box([2, 2.5, 13.4], [30, 10.2, 14], default="shelf_back", comment="shelf back")
box([2, 10.2, 0.6], [30, 10.8, 14], default="shelf_ceil", comment="shelf ceiling")
box([2.4, 10.0, 0.9], [29.6, 10.2, 1.6], default="glow", comment="shelf light strip")

# --- TV set (flush in the cabinet): thick graphite bezel around a recessed, curved-looking tube
box([2, 10.8, 1], [GX0, 31, 4], default="graphite", comment="bezel L")
box([GX1, 10.8, 1], [30, 31, 4], default="graphite", comment="bezel R")
box([GX0, GY1, 1], [GX1, 31, 4], default="graphite", comment="bezel top")
box([GX0, 12.8, 1], [GX1, GY0, 4], default="graphite", comment="bezel bottom")
box([15.2, 12.95, 0.8], [16.8, 13.25, 1], default="metal", comment="nameplate blip")
box([GX0, GY0, 1.9], [GX1, GY1, 5], {"north": "glass"}, "black", comment="tube glass")
box([GX0 + 0.5, GY0 + 0.5, 1.75], [GX1 - 0.5, GY1 - 0.5, 1.9], {"north": "glass"}, "black", comment="tube bulge")
box([GX0 - 0.4, GY0 - 0.4, 1.4], [GX1 + 0.4, GY0, 1.9], default="graphite_l", comment="inner lip bottom")
box([GX0 - 0.4, GY1, 1.4], [GX1 + 0.4, GY1 + 0.4, 1.9], default="graphite_l", comment="inner lip top")
box([GX0 - 0.4, GY0, 1.4], [GX0, GY1, 1.9], default="graphite_l", comment="inner lip L")
box([GX1, GY0, 1.4], [GX1 + 0.4, GY1, 1.9], default="graphite_l", comment="inner lip R")
box([2, 10.4, 1], [30, 12.8, 4], {"north": "strip"}, "graphite_d", comment="speaker strip")
box([2, 10.8, 4], [30, 31, 14], default="graphite_d", comment="set body")

# --- the console on the shelf: adapter slab with the cube on top
box([9, 2.5, -0.4], [23, 4.0, 9.6], {"north": "adapter_front", "up": "adapter"}, "adapter", comment="adapter")
box([10.5, 4.0, 0.4], [21.5, 9.5, 9.4], {"north": "console_front", "east": "console_side", "west": "console_side"}, "indigo", comment="console")
PORT_X, PORT_Y, PORT_Z = 21.5 - 6.0 / 4.0, 9.5 - 17.5 / 4.0, 0.4   # centre of port 1 on the console front

# --- controller on the floor in front of the cabinet
PX0, PX1, PZ0, PZ1 = 10.5, 21.5, -14.0, -6.75


def pad_xz(px, py):
    """Authored x/z of a pixel of the pad_top tile (painted as seen by a player facing the TV, cable edge on top)."""
    return PX0 + (PW - px) / 4.0, PZ0 + (PH - py) / 4.0


box([PX0, 0, PZ0], [PX1, 1.8, PZ1], {"up": ("pad_top", "rot180")}, "violet", comment="pad core")
box([PX0 + 1, 0, PZ0 - 0.6], [PX1 - 1, 1.3, PZ0], default="violet", comment="pad lower edge")
box([PX0 - 1.2, 0, -16.0], [PX0 + 1.6, 1.7, -10.4], default="violet", rot={"origin": [PX0 + 0.2, 0.85, -13.2], "axis": "y", "angle": -22.5}, comment="grip L")
box([PX1 - 1.6, 0, -16.0], [PX1 + 1.2, 1.7, -10.4], default="violet", rot={"origin": [PX1 - 0.2, 0.85, -13.2], "axis": "y", "angle": 22.5}, comment="grip R")
ax, az = pad_xz(k(27), k(9))
box([ax - 1.0, 1.8, az - 1.0], [ax + 1.0, 2.35, az + 1.0], default="green", comment="A")
bx_, bz_ = pad_xz(k(19), k(14))
box([bx_ - 0.5, 1.8, bz_ - 0.5], [bx_ + 0.5, 2.15, bz_ + 0.5], default="red", comment="B")
x_x, x_z = pad_xz(k(33), k(13))
box([x_x - 0.5, 1.8, x_z - 1.1], [x_x + 0.5, 2.1, x_z + 1.1], default="grey", comment="X")
y_x, y_z = pad_xz(k(27), k(2))
box([y_x - 1.1, 1.8, y_z - 0.45], [y_x + 1.1, 2.1, y_z + 0.45], default="grey", comment="Y")
sx_, sz_ = pad_xz(k(9), k(7))
box([sx_ - 0.85, 1.8, sz_ - 0.85], [sx_ + 0.85, 2.9, sz_ + 0.85], default="grey", comment="analog stick")
box([sx_ - 0.6, 2.9, sz_ - 0.6], [sx_ + 0.6, 3.15, sz_ + 0.6], default="grey_d", comment="stick cap")
cx_, cz_ = pad_xz(k(27), k(18))
box([cx_ - 0.6, 1.8, cz_ - 0.6], [cx_ + 0.6, 2.4, cz_ + 0.6], default="yellow", comment="C stick")
dx_, dz_ = pad_xz(k(10), k(17))
box([dx_ - 1.35, 1.8, dz_ - 0.5], [dx_ + 1.35, 2.1, dz_ + 0.5], default="dpad", comment="d-pad H")
box([dx_ - 0.5, 1.8, dz_ - 1.35], [dx_ + 0.5, 2.1, dz_ + 1.35], default="dpad", comment="d-pad V")
box([PX0 + 1.0, 1.5, PZ1], [PX0 + 4.2, 2.2, PZ1 + 0.7], default="pad_b", comment="trigger L")
box([PX1 - 4.2, 1.5, PZ1], [PX1 - 1.0, 2.2, PZ1 + 0.7], default="pad_b", comment="trigger R")
box([15.2, 0.4, PZ1], [16.8, 1.1, PZ1 + 0.5], default="cable", comment="cable boot")
# cable: droops from port 1 over the shelf lip, down the front of the plinth, across the floor to the pad
pts = [(PORT_X, PORT_Y, 0.3), (PORT_X, 4.7, -0.7), (PORT_X + .1, 4.0, -1.5), (PORT_X + .3, 3.2, -2.2), (PORT_X + .4, 2.4, -2.8), (PORT_X + .4, 1.6, -3.2),
       (PORT_X, .9, -3.7), (19, .5, -4.6), (17.8, .45, -5.6), (16.8, .55, -6.4), (16, .7, PZ1 + 0.2)]
for i in range(len(pts) - 1):
    a_, b_ = pts[i], pts[i + 1]
    th = 0.3
    box([min(a_[0], b_[0]) - th, min(a_[1], b_[1]) - th, min(a_[2], b_[2]) - th],
        [max(a_[0], b_[0]) + th, max(a_[1], b_[1]) + th, max(a_[2], b_[2]) + th], default="cable", comment="cable %d" % i)
box([PORT_X - 0.55, PORT_Y - 0.5, 0.2], [PORT_X + 0.55, PORT_Y + 0.5, 0.5], default="black", comment="plug")


# ---------------------------------------------------------------- output

def png(w, h, px):
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in px)

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def write(rel, data, mode="w"):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, mode + ("b" if isinstance(data, bytes) else "")) as f:
        f.write(data)


DISPLAY_INV = {
    "gui": {"rotation": [20, 200, 0], "translation": [-0.8, -2.6, 0], "scale": [0.26, 0.26, 0.26]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.1, 0.1, 0.1]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.2, 0.2, 0.2]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 2], "scale": [0.1, 0.1, 0.1]},
    "firstperson_righthand": {"rotation": [0, 160, 0], "translation": [-3, 4, 0], "scale": [0.1, 0.1, 0.1]},
    "firstperson_lefthand": {"rotation": [0, 160, 0], "translation": [3, 4, 0], "scale": [0.1, 0.1, 0.1]},
}

base_model = {"textures": {"t": "gbalink:item/crt", "particle": "gbalink:item/crt"}, "elements": elements}
inv = dict(base_model)
inv["gui_light"] = "side"
inv["display"] = DISPLAY_INV
write("models/item/crt.json", json.dumps(inv, indent=1) + "\n")
write("models/item/crt_placed.json", json.dumps(base_model, indent=1) + "\n")
write("items/crt.json", json.dumps({"model": {"type": "minecraft:model", "model": "gbalink:item/crt"}}, indent=2) + "\n")
write("items/crt_placed.json", json.dumps({"model": {"type": "minecraft:model", "model": "gbalink:item/crt_placed"}}, indent=2) + "\n")
write("textures/item/crt.png", png(N, N, atlas))

# consistency with the Java side (CrtGeometry)
JAVA = os.path.join(HERE, "..", "src", "main", "java", "com", "drew", "gba", "crt", "CrtGeometry.java")
src = open(JAVA).read()
for want in ("SCREEN_X0 = %g" % SX0, "SCREEN_X1 = %g" % SX1, "SCREEN_Y0 = %.5g" % SY0, "SCREEN_Y1 = %.5g" % SY1, "SCREEN_Z = %g" % SZ0):
    assert want in src, "CrtGeometry out of sync: " + want
print("ok:", len(elements), "elements, atlas tiles:", len(tiles))
