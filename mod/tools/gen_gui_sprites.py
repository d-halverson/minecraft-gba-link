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
    render("shoulder_pressed", 44, 10, make_capsule(44, 10, 38, 5.5, pressed=True, **lit))


if __name__ == "__main__":
    main()
