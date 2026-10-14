#!/usr/bin/env python3
"""Generates the anti-aliased GBA screen control sprites (D-pad, A/B buttons, Start/Select pills, shoulder bars)
into src/client/resources/assets/gbalink/textures/gui/. Stdlib only: signed distance fields + supersampling, and a
zlib/struct PNG writer. Every sprite is rendered at 4x its GUI size (TEX px = 4 * GUI px) with alpha, plus a
.png.mcmeta asking for linear filtering so the 4x art downsamples smoothly at GUI scales 2-4.

Run from anywhere:  python3 tools/gen_gui_sprites.py
All art is an original design (no console logos or trademarks)."""
import json
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "client", "resources", "assets", "gbalink", "textures", "gui")

K = 4    # texture pixels per GUI pixel
SS = 3   # supersamples per axis per texture pixel
L = (-0.7071, -0.7071)  # direction towards the light (top-left)


# ---------------------------------------------------------------- maths helpers
def clamp(v, a=0.0, b=1.0):
    return a if v < a else b if v > b else v


def smooth(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(len(a)))


def mul(c, f):
    return tuple(clamp(v * f, 0, 255) for v in c[:3])


def cov(d):
    """Edge coverage from a signed distance in GUI px (1 texture px = 1/K GUI px)."""
    return clamp(0.5 - d * K)


def over(dst, rgb, a):
    """Composite non-premultiplied rgb with alpha a over dst = (r, g, b, a)."""
    if a <= 0:
        return dst
    da = dst[3]
    oa = a + da * (1 - a)
    if oa <= 0:
        return (0, 0, 0, 0)
    return tuple((rgb[i] * a + dst[i] * da * (1 - a)) / oa for i in range(3)) + (oa,)


def smin(a, b, k):
    h = clamp(0.5 + 0.5 * (b - a) / k)
    return b + (a - b) * h - k * h * (1 - h)


def sd_round_box(px, py, hx, hy, r):
    qx, qy = abs(px) - hx + r, abs(py) - hy + r
    return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - r


def sd_tri(px, py, pts):
    """Signed distance (approx, exact outside edges) of a convex triangle given CCW-agnostic points."""
    # orient
    (ax, ay), (bx, by), (cx, cy) = pts
    if (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) < 0:
        pts = [pts[0], pts[2], pts[1]]
    d = -1e9
    for i in range(3):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % 3]
        ex, ey = x1 - x0, y1 - y0
        ln = math.hypot(ex, ey)
        # outward normal for CCW polygon in y-down coords flips; compute via test with centroid
        nx, ny = ey / ln, -ex / ln
        cxm = sum(p[0] for p in pts) / 3
        cym = sum(p[1] for p in pts) / 3
        if (cxm - x0) * nx + (cym - y0) * ny > 0:
            nx, ny = -nx, -ny
        d = max(d, (px - x0) * nx + (py - y0) * ny)
    return d - 0.25  # slightly rounded look


def grad(f, x, y, e=0.06):
    gx = f(x + e, y) - f(x - e, y)
    gy = f(x, y + e) - f(x, y - e)
    n = math.hypot(gx, gy) or 1
    return gx / n, gy / n


# ---------------------------------------------------------------- PNG output
def write_png(path, w, h, pix):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            r, g, b, a = pix[y * w + x]
            raw += bytes((int(clamp(r, 0, 255) + 0.5), int(clamp(g, 0, 255) + 0.5), int(clamp(b, 0, 255) + 0.5), int(clamp(a) * 255 + 0.5)))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)
    with open(path + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": True, "clamp": True}}, f)


def render(name, gw, gh, shade):
    """shade(x, y) -> (r, g, b, a) non-premultiplied, with x, y in GUI px. Supersampled to a K*gw by K*gh PNG."""
    w, h = gw * K, gh * K
    pix = []
    for py in range(h):
        for px in range(w):
            ar = ag = ab = aa = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) / K
                    y = (py + (sy + 0.5) / SS) / K
                    r, g, b, a = shade(x, y)
                    ar += r * a
                    ag += g * a
                    ab += b * a
                    aa += a
            n = SS * SS
            if aa <= 0:
                pix.append((0, 0, 0, 0))
            else:
                pix.append((ar / aa, ag / aa, ab / aa, aa / n))
    os.makedirs(OUT, exist_ok=True)
    write_png(os.path.join(OUT, name + ".png"), w, h, pix)
    print("wrote", name, w, "x", h)


# ---------------------------------------------------------------- D-pad
DP = 40            # canvas (GUI px)
DC = DP / 2.0
ARM_L, ARM_W, ARM_R = 17.0, 6.0, 2.4   # half length, half width, end rounding
DP_BASE, DP_HI, DP_SH = (60, 56, 94), (128, 122, 178), (24, 21, 44)
ARROW_D = 11.6
DIRS = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}


def cross_sd(x, y):
    px, py = x - DC, y - DC
    return smin(sd_round_box(px, py, ARM_L, ARM_W, ARM_R), sd_round_box(px, py, ARM_W, ARM_L, ARM_R), 2.0)


def arrow_pts(dx, dy):
    # small triangle pointing outward along (dx, dy), centred at ARROW_D
    cx, cy = DC + dx * ARROW_D, DC + dy * ARROW_D
    tip, back, half = 1.9, 1.5, 2.1
    px_, py_ = -dy, dx
    return [(cx + dx * tip, cy + dy * tip),
            (cx - dx * back + px_ * half, cy - dy * back + py_ * half),
            (cx - dx * back - px_ * half, cy - dy * back - py_ * half)]


def make_dpad(pressed):
    pressed = set(pressed)
    arrows = {n: arrow_pts(*v) for n, v in DIRS.items()}
    tilt_off = 0.35 if pressed else 0.0

    def shade(x, y):
        px = (0, 0, 0, 0)
        # soft drop shadow (shorter when a direction is held)
        so = 0.7 if pressed else 1.3
        ds = cross_sd(x - so * 0.6, y - so)
        sa = 0.50 * (1 - smooth(-1.4, 2.6, ds))
        px = over(px, (8, 6, 22), sa)
        d = cross_sd(x, y)
        c = cov(d)
        if c <= 0:
            return px
        gx, gy = grad(cross_sd, x, y)
        depth = -d
        # base face with a gentle diagonal gradient
        face = mix(mul(DP_BASE, 1.12), mul(DP_BASE, 0.9), clamp(((x - DC) + (y - DC)) / (2 * ARM_L) * 0.5 + 0.5))
        # bevel: lit top-left edges, shaded bottom-right edges
        facing = gx * L[0] + gy * L[1]
        bev = clamp(1 - depth / 1.7) ** 1.5
        if facing > 0:
            face = mix(face, DP_HI, bev * clamp(facing * 1.2) * 0.85)
        else:
            face = mix(face, DP_SH, bev * clamp(-facing * 1.2) * 0.8)
        # thin dark rim so the edge reads on the indigo body
        face = mix(face, DP_SH, clamp(1 - depth / 0.45) * 0.55)
        # centre dimple: concave, so the top-left wall is in shade and the bottom-right wall catches light
        cx, cy = x - DC, y - DC
        r = math.hypot(cx, cy)
        RD = 4.4
        if r < RD + 0.5:
            wall = smooth(0.2, 1.0, r / RD)
            dirx, diry = (cx / r, cy / r) if r > 1e-6 else (0, 0)
            f = dirx * L[0] + diry * L[1]  # >0 on the top-left wall
            if f > 0:
                face = mix(face, DP_SH, wall * f * 0.85)
            else:
                face = mix(face, DP_HI, wall * -f * 0.7)
            face = mul(face, 1 - 0.10 * (1 - wall))  # dimple floor slightly darker
            # ring edge antialias
            ring = 1 - smooth(RD - 0.15, RD + 0.15, r)
            face = mix(face, mul(face, 1), 1 - ring)
        # pressed arm(s): darker, tipping inwards
        k = 1.0
        for n in pressed:
            vx, vy = DIRS[n]
            proj = cx * vx + cy * vy
            lat = abs(cx * -vy + cy * vx)
            wgt = smooth(1.0, 7.0, proj) * (1 - smooth(6.0, 9.5, lat))
            k *= 1 - 0.50 * wgt * (0.55 + 0.45 * clamp(proj / ARM_L))
            # opposite arm lifts a touch
            wo = smooth(1.0, 7.0, -proj) * (1 - smooth(6.0, 9.5, lat))
            k *= 1 + 0.07 * wo
        face = mul(face, k)
        # engraved arrows (light lower-right lip + dark recess)
        for n, pts in arrows.items():
            hl = cov(sd_tri(x - 0.45, y - 0.45, pts)) * 0.30
            face = mix(face, DP_HI, hl)
            dk = cov(sd_tri(x, y, pts)) * (0.62 if n not in pressed else 0.75)
            face = mix(face, (12, 10, 26), dk)
        return over(px, face, c)

    return shade


# ---------------------------------------------------------------- A / B buttons
BT = 26
BC = BT / 2.0
BR = 8.0
BTN_HI, BTN_LO = (226, 220, 242), (150, 142, 186)


def make_button(pressed):
    cy = BC + (1.0 if pressed else 0.0)

    def shade(x, y):
        px = (0, 0, 0, 0)
        so = 0.3 if pressed else 1.4
        ds = math.hypot(x - BC - so * 0.35, y - (BC + so)) - BR
        px = over(px, (8, 6, 24), (0.42 if pressed else 0.5) * (1 - smooth(-1.3, 2.4 if not pressed else 1.6, ds)))
        dx, dy = x - BC, y - cy
        d = math.hypot(dx, dy) - BR
        c = cov(d)
        if c <= 0:
            return px
        # radial gradient with the light source up-left
        lx, ly = dx + 0.32 * BR, dy + 0.36 * BR
        t = clamp(math.hypot(lx, ly) / (1.55 * BR))
        face = mix(BTN_HI, BTN_LO, t ** 1.15)
        # convex rim: shade bottom-right edge, lift top-left edge
        depth = -d
        r = math.hypot(dx, dy) or 1
        facing = (dx / r) * L[0] + (dy / r) * L[1]
        bev = clamp(1 - depth / 1.9) ** 1.4
        if facing > 0:
            face = mix(face, (246, 242, 255), bev * facing * 0.35)
        else:
            face = mix(face, (86, 78, 126), bev * -facing * 0.75)
        face = mix(face, (96, 88, 134), clamp(1 - depth / 0.4) * 0.45)
        if pressed:
            face = mix(mul(face, 0.88), (104, 92, 170), 0.42)
        # specular highlight
        sxx, syy = (dx + 3.2) / 3.0, (dy + 3.7) / 1.9
        sp = 1 - smooth(0.2, 1.0, math.hypot(sxx, syy))
        face = mix(face, (255, 255, 255), sp * (0.28 if pressed else 0.62))
        return over(px, face, c)

    return shade


# ---------------------------------------------------------------- Start / Select pill, shoulder bars
def make_capsule(gw, gh, bw, bh, base, hi, sh, pressed):
    cx, cy = gw / 2.0, gh / 2.0 + (0.7 if pressed else 0.0)
    r = bh / 2.0

    def sd(x, y):
        return sd_round_box(x - cx, y - cy, bw / 2.0, bh / 2.0, r)

    def shade(x, y):
        px = (0, 0, 0, 0)
        so = 0.3 if pressed else 0.9
        px = over(px, (8, 6, 24), 0.42 * (1 - smooth(-0.9, 1.8, sd(x - 0.3, y - so) + (cy - gh / 2.0) * 0)))
        d = sd(x, y)
        c = cov(d)
        if c <= 0:
            return px
        gx, gy = grad(sd, x, y)
        depth = -d
        t = clamp((y - (cy - bh / 2.0)) / bh)
        face = mix(mul(base, 1.18), mul(base, 0.84), t)
        facing = gx * L[0] + gy * L[1]
        bev = clamp(1 - depth / 1.1) ** 1.5
        if facing > 0:
            face = mix(face, hi, bev * clamp(facing * 1.3) * 0.8)
        else:
            face = mix(face, sh, bev * clamp(-facing * 1.3) * 0.75)
        face = mix(face, sh, clamp(1 - depth / 0.35) * 0.5)
        if pressed:
            face = mul(face, 0.82)
        return over(px, face, c)

    return shade


# ---------------------------------------------------------------- power switch + LED
SW_W, SW_H = 40, 14
KNOB_HX, KNOB_HY = 7.2, 5.0
KNOB_TRAVEL = 9.5   # knob centre offset from the middle: +ON (right) / -OFF (left)


def make_switch(on):
    cx, cy = SW_W / 2.0, SW_H / 2.0
    hx, hy = 17.0, 3.9
    kx = cx + (KNOB_TRAVEL if on else -KNOB_TRAVEL)
    ky = cy - 0.3

    def groove(x, y):
        return sd_round_box(x - cx, y - cy, hx, hy, hy)

    def knob(x, y):
        return sd_round_box(x - kx, y - ky, KNOB_HX, KNOB_HY, 2.4)

    def shade(x, y):
        px = (0, 0, 0, 0)
        gd = groove(x, y)
        gc = cov(gd)
        if gc > 0:
            t = clamp((y - (cy - hy)) / (2 * hy))
            face = mix((8, 6, 20), (30, 26, 56), t ** 1.4)        # recessed: dark at the top, light lip below
            if on:   # a faint green glow on the ON half
                face = mix(face, (30, 90, 56), 0.35 * smooth(cx, cx + hx, x))
            depth = -gd
            face = mix(face, (96, 88, 140), clamp(1 - depth / 0.6) * smooth(0.45, 0.9, t) * 0.8)  # lower lip
            px = over(px, face, gc)
        # knob drop shadow on the groove
        kd = knob(x - 0.5, y - 1.0)
        px = over(px, (4, 3, 12), 0.55 * (1 - smooth(-1.0, 1.8, kd)) * (1 if gc > 0 else 0.4))
        d = knob(x, y)
        c = cov(d)
        if c <= 0:
            return px
        gx, gy = grad(knob, x, y)
        depth = -d
        t = clamp((y - (ky - KNOB_HY)) / (2 * KNOB_HY))
        face = mix((214, 208, 244), (140, 130, 186), t ** 1.1)
        facing = gx * L[0] + gy * L[1]
        bev = clamp(1 - depth / 1.2) ** 1.5
        if facing > 0:
            face = mix(face, (250, 248, 255), bev * clamp(facing * 1.3) * 0.8)
        else:
            face = mix(face, (74, 66, 118), bev * clamp(-facing * 1.3) * 0.8)
        face = mix(face, (74, 66, 118), clamp(1 - depth / 0.35) * 0.5)
        for i in range(-2, 3):   # grip ridges
            rx = kx + i * 2.3
            line = 1 - smooth(0.12, 0.5, abs(x - rx))
            hl = 1 - smooth(0.12, 0.5, abs(x - rx - 0.55))
            face = mix(face, (84, 76, 128), line * 0.6 * smooth(0.0, 1.2, depth))
            face = mix(face, (246, 244, 255), hl * 0.35 * smooth(0.0, 1.2, depth))
        return over(px, face, c)

    return shade


def make_led(color):
    cx = cy = 4.0
    hi = tuple(min(255, v + 90) for v in color)

    def shade(x, y):
        r = math.hypot(x - cx, y - cy)
        px = (0, 0, 0, 0)
        px = over(px, color, 0.30 * (1 - smooth(2.2, 3.9, r)))       # glow
        px = over(px, (14, 12, 30), cov(r - 3.0))                      # bezel ring
        core = cov(r - 2.3)
        t = clamp(math.hypot(x - cx + 0.7, y - cy + 0.8) / 2.6)
        px = over(px, mix(hi, mul(color, 0.7), t), core)
        sp = 1 - smooth(0.1, 0.9, math.hypot(x - cx + 0.8, y - cy + 0.9))
        px = over(px, (255, 255, 255), sp * 0.7 * core)
        return px

    return shade


# ---------------------------------------------------------------- volume thumb-wheels + icons
WH_W, WH_H = 40, 12          # wheel sprite (GUI px): a horizontal thumb-wheel seen through a slot, like the GBA's dial
WH_PHASES = 8                # ridge positions; the wheel "turns" as the volume changes
WH_RIDGES = 22               # ridges around the whole wheel
WH_HX, WH_HY = 16.6, 4.1     # visible half-size of the wheel inside the slot
WHEEL_TINT = {"gba": (168, 156, 226), "mc": (128, 178, 112)}


def make_wheel(tint, phase):
    cx, cy = WH_W / 2.0, WH_H / 2.0
    pitch = 2 * math.pi / WH_RIDGES

    def slot(x, y):
        return sd_round_box(x - cx, y - cy, WH_W / 2.0 - 0.6, WH_H / 2.0 - 0.6, 3.2)

    def wheel(x, y):
        return sd_round_box(x - cx, y - cy, WH_HX, WH_HY, 1.3)

    def shade(x, y):
        px = (0, 0, 0, 0)
        sd = slot(x, y)
        sc = cov(sd)
        if sc > 0:
            t = clamp((y - (cy - WH_H / 2.0)) / WH_H)
            face = mix((6, 5, 16), (28, 24, 52), t ** 1.5)                          # recessed slot
            face = mix(face, (96, 88, 140), clamp(1 - (-sd) / 0.55) * smooth(0.5, 0.95, t) * 0.8)   # lit lower lip
            px = over(px, face, sc)
        d = wheel(x, y)
        c = cov(d)
        if c <= 0:
            return px
        u = clamp((x - cx) / WH_HX, -1, 1)
        th = math.asin(u)                       # angle around the wheel axis
        lit = clamp(0.5 + 0.55 * math.cos(th - 0.42))      # light from the upper left
        face = mul(tint, 0.42 + 0.78 * lit)
        phi = (th / pitch + phase / WH_PHASES) % 1.0       # position inside one ridge pitch
        a = abs(phi - 0.5)                                  # 0 at the ridge crest, 0.5 in the groove (phi==0)
        g = 1 - smooth(0.0, 0.2, 0.5 - a)                   # groove darkness near phi==0
        crest = 1 - smooth(0.0, 0.14, abs(phi - 0.5))
        face = mix(face, (20, 16, 44), g * 0.65 * (0.4 + 0.6 * math.cos(th)))
        face = mix(face, (255, 255, 255), crest * 0.22 * lit)
        # cylinder end shading: dark where the wheel curves away into the slot
        face = mul(face, 0.55 + 0.45 * smooth(0.0, 0.5, math.cos(th)) ** 0.7)
        # soft top highlight and bottom shade (the wheel is also slightly rounded vertically)
        vt = (y - (cy - WH_HY)) / (2 * WH_HY)
        face = mix(face, (255, 255, 255), (1 - smooth(0.0, 0.3, vt)) * 0.25)
        face = mix(face, (10, 8, 30), smooth(0.7, 1.0, vt) * 0.35)
        face = mix(face, (18, 14, 40), clamp(1 - (-d) / 0.4) * 0.5)
        # shadow cast by the wheel on the slot below its edge is implied by the dark slot
        return over(px, face, c)

    return shade


def sd_convex(px, py, pts):
    """Signed distance (negative inside, exact for the nearest edge plane) of a convex polygon."""
    cxm = sum(p[0] for p in pts) / len(pts)
    cym = sum(p[1] for p in pts) / len(pts)
    d = -1e9
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        ex, ey = x1 - x0, y1 - y0
        ln = math.hypot(ex, ey)
        nx, ny = ey / ln, -ex / ln
        if (cxm - x0) * nx + (cym - y0) * ny > 0:
            nx, ny = -nx, -ny
        d = max(d, (px - x0) * nx + (py - y0) * ny)
    return d


def make_speaker():
    body = [(1.3, 3.6), (3.4, 3.6), (3.4, 6.4), (1.3, 6.4)]
    cone = [(3.4, 3.6), (6.0, 1.4), (6.0, 8.6), (3.4, 6.4)]
    wc = (5.8, 5.0)

    def waves(x, y):
        r = math.hypot(x - wc[0], y - wc[1])
        ang = math.atan2(y - wc[1], x - wc[0])
        if abs(ang) > 0.75 or x < wc[0]:
            return 9.0
        return min(abs(r - 2.4), abs(r - 3.9)) - 0.32

    def sd(x, y):
        return min(sd_convex(x, y, body) - 0.15, sd_convex(x, y, cone) - 0.15, waves(x, y))

    def shade(x, y):
        px = (0, 0, 0, 0)
        px = over(px, (6, 5, 18), 0.55 * (1 - smooth(-0.3, 0.9, sd(x - 0.4, y - 0.5))))
        c = cov(sd(x, y))
        if c <= 0:
            return px
        t = clamp(y / 10.0)
        return over(px, mix((236, 228, 255), (176, 164, 224), t), c)

    return shade


def make_grass():
    top = [(5.0, 0.7), (9.4, 2.9), (5.0, 5.1), (0.6, 2.9)]
    left = [(0.6, 2.9), (5.0, 5.1), (5.0, 9.5), (0.6, 7.3)]
    right = [(5.0, 5.1), (9.4, 2.9), (9.4, 7.3), (5.0, 9.5)]
    hexa = [(5.0, 0.7), (9.4, 2.9), (9.4, 7.3), (5.0, 9.5), (0.6, 7.3), (0.6, 2.9)]

    def shade(x, y):
        px = (0, 0, 0, 0)
        hd = sd_convex(x, y, hexa)
        px = over(px, (6, 5, 18), 0.5 * (1 - smooth(-0.3, 0.9, sd_convex(x - 0.3, y - 0.4, hexa))))
        px = over(px, (14, 12, 30), cov(hd - 0.28))     # dark outline
        if cov(hd) <= 0:
            return px
        for pts, base, edge_green in ((left, (146, 104, 70), 2.3), (right, (112, 78, 52), 2.3), (top, (110, 190, 76), 0)):
            c = cov(sd_convex(x, y, pts))
            if c <= 0:
                continue
            col = base
            if pts is top:
                col = mix((136, 212, 96), (92, 168, 64), clamp((y - 0.7) / 4.4))
                # speckles of lighter / darker grass
                n = math.sin(x * 7.1 + y * 3.3) * math.sin(x * 2.9 - y * 5.7)
                col = mix(col, (150, 224, 108) if n > 0.2 else (80, 150, 56), clamp(abs(n)) * 0.35)
            elif pts is left:
                # grass fringe along the top edge of the side
                edge = (y - (2.9 + (x - 0.6) * 0.5))
                col = mix(col, (96, 164, 66), 1 - smooth(1.0, 1.7, edge))
                col = mix(col, mul(col, 0.8), 0.35 * smooth(0.2, 1.0, math.sin(y * 4.0 + x)))
            else:
                edge = (y - (5.1 - (x - 5.0) * 0.5))
                col = mix(col, (74, 132, 52), 1 - smooth(1.0, 1.7, edge))
                col = mix(col, mul(col, 0.8), 0.35 * smooth(0.2, 1.0, math.sin(y * 4.0 - x)))
            px = over(px, col, c)
        return px

    return shade


def make_cable():
    pts = []
    for i in range(33):
        t = i / 32.0
        x = (1 - t) ** 2 * 3.2 + 2 * (1 - t) * t * 6.0 + t * t * 8.8
        y = (1 - t) ** 2 * 3.4 + 2 * (1 - t) * t * 9.4 + t * t * 3.4
        pts.append((x, y))

    def sd_wire(x, y):
        best = 1e9
        for i in range(len(pts) - 1):
            (x0, y0), (x1, y1) = pts[i], pts[i + 1]
            dx, dy = x1 - x0, y1 - y0
            t = clamp(((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy))
            best = min(best, math.hypot(x - x0 - t * dx, y - y0 - t * dy))
        return best - 0.65

    def plug(x, y, cx):
        return sd_round_box(x - cx, y - 3.0, 1.25, 1.9, 0.5)

    def shade(x, y):
        px = (0, 0, 0, 0)
        sd = min(sd_wire(x, y), plug(x, y, 1.6), plug(x, y, 10.4))
        px = over(px, (6, 5, 18), 0.5 * (1 - smooth(-0.3, 0.9, min(sd_wire(x - 0.3, y - 0.4), plug(x - 0.3, y - 0.4, 1.6), plug(x - 0.3, y - 0.4, 10.4)))))
        c = cov(sd)
        if c <= 0:
            return px
        if min(plug(x, y, 1.6), plug(x, y, 10.4)) <= sd_wire(x, y):
            col = mix((240, 234, 255), (170, 158, 224), clamp((y - 1.1) / 3.8))
        else:
            col = mix((150, 226, 160), (84, 176, 108), clamp((y - 3.0) / 5.0))
        return over(px, col, c)

    return shade


def main():
    import sys
    only = sys.argv[1:]
    if only:
        global render
        _r = render
        render = lambda name, *a: _r(name, *a) if any(name.startswith(o) for o in only) else None
    render("dpad", DP, DP, make_dpad([]))
    for n in DIRS:
        render("dpad_" + n, DP, DP, make_dpad([n]))
    for v in ("up", "down"):
        for h in ("left", "right"):
            render("dpad_%s_%s" % (v, h), DP, DP, make_dpad([v, h]))
    render("button", BT, BT, make_button(False))
    render("button_pressed", BT, BT, make_button(True))
    pill = dict(base=(60, 55, 98), hi=(128, 120, 176), sh=(24, 21, 46))
    render("pill", 28, 12, make_capsule(28, 12, 22, 6.5, pressed=False, **pill))
    render("pill_pressed", 28, 12, make_capsule(28, 12, 22, 6.5, pressed=True, **pill))
    render("shoulder", 44, 10, make_capsule(44, 10, 38, 5.5, pressed=False, **pill))
    lit = dict(base=(196, 186, 240), hi=(244, 240, 255), sh=(120, 108, 176))
    render("power_on", SW_W, SW_H, make_switch(True))
    render("power_off", SW_W, SW_H, make_switch(False))
    render("led_green", 8, 8, make_led((64, 232, 104)))
    render("led_red", 8, 8, make_led((240, 56, 56)))
    render("shoulder_pressed", 44, 10, make_capsule(44, 10, 38, 5.5, pressed=True, **lit))
    for kind, tint in WHEEL_TINT.items():
        for ph in range(WH_PHASES):
            render("wheel_%s_%d" % (kind, ph), WH_W, WH_H, make_wheel(tint, ph))
    render("icon_speaker", 10, 10, make_speaker())
    render("icon_grass", 10, 10, make_grass())
    render("icon_cable", 12, 10, make_cable())


if __name__ == "__main__":
    main()
