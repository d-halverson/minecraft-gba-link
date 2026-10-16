#!/usr/bin/env python3
"""Generate small Game Boy / Game Boy Color test ROMs (hand assembled LR35902, no external tools, nothing downloaded).

    make_gb_rom.py <outdir>      writes gb_dmg.gb, gb_cgb.gbc, gb_sgb.gb

Every ROM runs the same program (variants are noted below):

  * boot: LCD off, tile data (font + patterns) copied to VRAM, a 20x18 tile map copied to 9800, one sprite in OAM,
    DMG palettes (BGP/OBP0 = E4) or, for the CGB ROM, eight BG palettes (one per map row, a rainbow) and sprite palette 0
    through the colour RAM registers, a 440 Hz square tone on channel 1, LCD on.
  * screen: a solid border, "GAME BOY", "DMG TEST" / "CGB TEST" / "SGB TEST", two rows of the four shades (colour
    test), a checker row, "A" with a three digit counter, eight boxes that show the joypad (see below), and a sprite.
  * every frame (after waiting for the start of V-blank): the counter digits and the joypad boxes are redrawn,
    the joypad is read through P1 (0xFF00), a 16 bit frame counter is incremented, and
      - on a new press of A the BCD counter (three digits) is incremented and stored in cartridge RAM,
      - on a new press of START (RTC variant only) the clock is latched (0 then 1 to 0x6000) and seconds, minutes and
        hours (RTC registers 08h-0Ah) are stored in WRAM.
  * cartridge RAM (battery) holds the magic "GBS1" at A000 and the three counter digits at A004-A006; they are
    restored at boot, so a save made by one core and loaded into another shows the same counter.

Variants:
  gb_dmg.gb   DMG only, MBC3+TIMER+RAM+BATTERY (0x10), 8 KiB RAM, with the RTC latch code
  gb_cgb.gbc  CGB only (flag 0xC0), MBC5+RAM+BATTERY (0x1B), manufacturer code "GBCT", CGB palettes
  gb_sgb.gb   DMG with the SGB flag (0x03, old licensee 0x33), MBC3+RAM+BATTERY (0x13): must still be 160x144 DMG

WRAM contract used by the native tests (all single bytes):
  C001 joypad now (bit0 Right, 1 Left, 2 Up, 3 Down, 4 A, 5 B, 6 Select, 7 Start; 1 = pressed)
  C002 joypad previous frame        C003/C004 frame counter (lo, hi)
  C010 C011 C012  counter digits (hundreds, tens, ones)
  C020 C021 C022  RTC seconds, minutes, hours as read after the latch (RTC variant)   C023  1 once latched
"""
import os
import sys

# --------------------------------------------------------------------------------------------- assembler
REG = {'b': 0, 'c': 1, 'd': 2, 'e': 3, 'h': 4, 'l': 5, 'hl': 6, 'a': 7}


class Asm:
    def __init__(self, base):
        self.base = base
        self.code = bytearray()
        self.labels = {}
        self.fix = []

    def pc(self):
        return self.base + len(self.code)

    def label(self, name):
        self.labels[name] = self.pc()

    def b(self, *bs):
        self.code.extend(bs)

    # loads
    def ld_r_n(self, r, n):      self.b(0x06 | REG[r] << 3, n & 0xFF)
    def ld_r_r(self, d, s):      self.b(0x40 | REG[d] << 3 | REG[s])
    def ld_hl_nn(self, v):       self._w16(0x21, v)
    def ld_de_nn(self, v):       self._w16(0x11, v)
    def ld_bc_nn(self, v):       self._w16(0x01, v)
    def ld_sp_nn(self, v):       self._w16(0x31, v)
    def ld_hl_lbl(self, l):      self._l16(0x21, l)
    def ld_de_lbl(self, l):      self._l16(0x11, l)
    def ld_a_hlp(self):          self.b(0x2A)
    def ld_hlp_a(self):          self.b(0x22)
    def ld_de_a(self):           self.b(0x12)
    def ld_a_de(self):           self.b(0x1A)
    def ldh_n_a(self, n):        self.b(0xE0, n & 0xFF)
    def ldh_a_n(self, n):        self.b(0xF0, n & 0xFF)
    def ld_nn_a(self, nn):       self._w16(0xEA, nn)
    def ld_a_nn(self, nn):       self._w16(0xFA, nn)
    # arithmetic
    def inc_r(self, r):          self.b(0x04 | REG[r] << 3)
    def dec_r(self, r):          self.b(0x05 | REG[r] << 3)
    def inc_hl16(self):          self.b(0x23)
    def inc_de(self):            self.b(0x13)
    def inc_bc(self):            self.b(0x03)
    def dec_bc(self):            self.b(0x0B)
    def dec_hl16(self):          self.b(0x2B)
    def add_hl_de(self):         self.b(0x19)
    def add_a_n(self, n):        self.b(0xC6, n & 0xFF)
    def cp_n(self, n):           self.b(0xFE, n & 0xFF)
    def and_n(self, n):          self.b(0xE6, n & 0xFF)
    def or_r(self, r):           self.b(0xB0 | REG[r])
    def and_r(self, r):          self.b(0xA0 | REG[r])
    def xor_a(self):             self.b(0xAF)
    def cpl(self):               self.b(0x2F)
    def swap_a(self):            self.b(0xCB, 0x37)
    def rlc_r(self, r):          self.b(0xCB, 0x00 | REG[r])
    def di(self):                self.b(0xF3)
    def nop(self):               self.b(0x00)
    def ret(self):               self.b(0xC9)
    def push_bc(self):           self.b(0xC5)
    def pop_bc(self):            self.b(0xC1)
    # control flow
    def jp(self, l):             self._l16(0xC3, l)
    def call(self, l):           self._l16(0xCD, l)
    def jr(self, l, cc=None):
        op = {None: 0x18, 'nz': 0x20, 'z': 0x28, 'nc': 0x30, 'c': 0x38}[cc]
        self.b(op, 0)
        self.fix.append((len(self.code) - 1, l, 'rel'))

    def _w16(self, op, v):
        self.b(op, v & 0xFF, (v >> 8) & 0xFF)

    def _l16(self, op, l):
        self.b(op, 0, 0)
        self.fix.append((len(self.code) - 2, l, 'abs'))

    def resolve(self):
        for pos, name, kind in self.fix:
            t = self.labels[name]
            if kind == 'abs':
                self.code[pos] = t & 0xFF
                self.code[pos + 1] = t >> 8
            else:
                off = t - (self.base + pos + 1)
                assert -128 <= off <= 127, (name, off)
                self.code[pos] = off & 0xFF
        return bytes(self.code)


# --------------------------------------------------------------------------------------------- graphics data
FONT = {
    'G': '01110 10001 10000 10111 10001 10001 01110',
    'A': '01110 10001 10001 11111 10001 10001 10001',
    'M': '10001 11011 10101 10101 10001 10001 10001',
    'E': '11111 10000 10000 11110 10000 10000 11111',
    'B': '11110 10001 10001 11110 10001 10001 11110',
    'O': '01110 10001 10001 10001 10001 10001 01110',
    'Y': '10001 10001 01010 00100 00100 00100 00100',
    'T': '11111 00100 00100 00100 00100 00100 00100',
    'S': '01111 10000 10000 01110 00001 00001 11110',
    'D': '11110 10001 10001 10001 10001 10001 11110',
    'C': '01110 10001 10000 10000 10000 10001 01110',
    '0': '01110 10001 10011 10101 11001 10001 01110',
    '1': '00100 01100 00100 00100 00100 00100 01110',
    '2': '01110 10001 00001 00010 00100 01000 11111',
    '3': '11110 00001 00001 01110 00001 00001 11110',
    '4': '00010 00110 01010 10010 11111 00010 00010',
    '5': '11111 10000 11110 00001 00001 10001 01110',
    '6': '00110 01000 10000 11110 10001 10001 01110',
    '7': '11111 00001 00010 00100 01000 01000 01000',
    '8': '01110 10001 10001 01110 10001 10001 01110',
    '9': '01110 10001 10001 01111 00001 00010 01100',
}
TILE_BLANK, TILE_SOLID3, TILE_CHECK, TILE_SOLID1, TILE_SOLID2 = 0, 1, 2, 3, 4
TILE_DIGIT = 0x10                       # 0x10..0x19
LETTER_ID = {ch: 0x20 + i for i, ch in enumerate('GAMEBOYTSDC')}


def tile_from_rows(rows):               # rows: 8 pairs (low plane, high plane)
    out = bytearray()
    for lo, hi in rows:
        out += bytes((lo, hi))
    return bytes(out)


def make_tiles():
    tiles = {}
    tiles[TILE_BLANK] = bytes(16)
    tiles[TILE_SOLID3] = bytes([0xFF]) * 16
    tiles[TILE_CHECK] = tile_from_rows([((0xAA, 0x55) if r % 2 == 0 else (0x55, 0xAA)) for r in range(8)])
    tiles[TILE_SOLID1] = tile_from_rows([(0xFF, 0x00)] * 8)
    tiles[TILE_SOLID2] = tile_from_rows([(0x00, 0xFF)] * 8)
    for ch, spec in FONT.items():
        rows = []
        for r in spec.split():
            v = int(r, 2) << 2
            rows.append((v, v))
        rows.append((0, 0))
        t = tile_from_rows(rows)
        if ch.isdigit():
            tiles[TILE_DIGIT + int(ch)] = t
        else:
            tiles[LETTER_ID[ch]] = t
    n = max(tiles) + 1
    return b''.join(tiles.get(i, bytes(16)) for i in range(n))


def text_row(cols, text, tile_of):
    row = [TILE_BLANK] * 20
    start = (20 - len(text)) // 2
    for i, ch in enumerate(text):
        if ch != ' ':
            row[start + i] = tile_of(ch)
    return row


def make_map(label):
    m = [[TILE_BLANK] * 20 for _ in range(18)]
    for x in range(20):
        m[0][x] = m[17][x] = TILE_SOLID3
    for y in range(18):
        m[y][0] = m[y][19] = TILE_SOLID3
    m[2] = text_row(20, 'GAME BOY', lambda c: LETTER_ID[c])
    m[4] = text_row(20, label + ' TEST', lambda c: LETTER_ID[c])
    for y in (6, 7):
        for i in range(12):
            m[y][4 + i] = (TILE_BLANK, TILE_SOLID1, TILE_SOLID2, TILE_SOLID3)[i % 4]
    for x in range(2, 18):
        m[9][x] = TILE_CHECK if x % 2 == 0 else TILE_BLANK
    m[11][6] = LETTER_ID['A']
    for i in range(3):
        m[11][8 + i] = TILE_DIGIT
    for i in range(8):
        m[13][6 + i] = TILE_SOLID1
    m[15][9] = TILE_SOLID1
    for y in range(18):
        m[y][0] = m[y][19] = TILE_SOLID3
    return b''.join(bytes(r) for r in m)


def rgb15(r, g, b):
    return (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10)


def cgb_palettes():
    hues = [(255, 0, 0), (255, 140, 0), (230, 220, 0), (0, 200, 0), (0, 200, 200), (0, 0, 255), (150, 0, 220), (255, 0, 160)]
    out = bytearray()
    for r, g, b in hues:
        cols = [(255, 255, 255),
                (255 - (255 - r) // 2, 255 - (255 - g) // 2, 255 - (255 - b) // 2),
                (r, g, b),
                (r // 4, g // 4, b // 4)]
        for c in cols:
            v = rgb15(*c)
            out += bytes((v & 0xFF, v >> 8))
    return bytes(out)


def make_attr():
    return b''.join(bytes([y % 8] * 20) for y in range(18))


# --------------------------------------------------------------------------------------------- program
def build_program(cgb, rtc, label):
    a = Asm(0x150)
    tiles = make_tiles()
    tmap = make_map(label)
    a.di()
    a.ld_sp_nn(0xFFFE)
    a.call('wait_vbl')
    a.xor_a()
    a.ldh_n_a(0x40)                       # LCD off
    # clear WRAM page C0xx
    a.ld_hl_nn(0xC000)
    a.ld_r_n('b', 0)
    a.label('clr')
    a.xor_a()
    a.ld_hlp_a()
    a.dec_r('b')
    a.jr('clr', 'nz')
    # tiles -> 8000
    a.ld_hl_lbl('tiles')
    a.ld_de_nn(0x8000)
    a.ld_bc_nn(len(tiles))
    a.call('copy')
    if cgb:
        # attributes: VBK=1, map -> 9800
        a.ld_r_n('a', 1)
        a.ldh_n_a(0x4F)
        a.ld_hl_lbl('attr')
        a.ld_de_nn(0x9800)
        a.call('copymap')
        a.xor_a()
        a.ldh_n_a(0x4F)
    a.ld_hl_lbl('map')
    a.ld_de_nn(0x9800)
    a.call('copymap')
    # OAM: sprite 0 at x=88, y=96 (screen 80, 80), tile 2 (checker)
    a.ld_hl_nn(0xFE00)
    a.ld_r_n('a', 96 + 16 - 16 + 0)
    a.ld_hlp_a()
    a.ld_r_n('a', 120 + 8 - 8)
    a.ld_hlp_a()
    a.ld_r_n('a', TILE_CHECK)
    a.ld_hlp_a()
    a.xor_a()
    a.ld_hlp_a()
    if cgb:
        # BG palettes: BCPS = 0x80 (auto increment), 64 bytes; OBJ palette 0: OCPS = 0x80, 8 bytes
        a.ld_r_n('a', 0x80)
        a.ldh_n_a(0x68)
        a.ld_hl_lbl('pal')
        a.ld_r_n('b', 64)
        a.label('bcp')
        a.ld_a_hlp()
        a.ldh_n_a(0x69)
        a.dec_r('b')
        a.jr('bcp', 'nz')
        a.ld_r_n('a', 0x80)
        a.ldh_n_a(0x6A)
        a.ld_hl_lbl('pal')
        a.ld_r_n('b', 8)
        a.label('ocp')
        a.ld_a_hlp()
        a.ldh_n_a(0x6B)
        a.dec_r('b')
        a.jr('ocp', 'nz')
    else:
        a.ld_r_n('a', 0xE4)
        a.ldh_n_a(0x47)
        a.ldh_n_a(0x48)
        a.ldh_n_a(0x49)
    # sound: channel 1 tone ~440 Hz
    for reg, val in ((0x26, 0x80), (0x25, 0xFF), (0x24, 0x77), (0x11, 0x80), (0x12, 0xF0), (0x13, 0xD6), (0x14, 0x86)):
        a.ld_r_n('a', val)
        a.ldh_n_a(reg)
    # cartridge RAM: enable, bank 0, magic check
    a.ld_r_n('a', 0x0A)
    a.ld_nn_a(0x0000)
    a.xor_a()
    a.ld_nn_a(0x4000)
    a.ld_a_nn(0xA000)
    a.cp_n(ord('G'))
    a.jr('fresh', 'nz')
    a.ld_a_nn(0xA001)
    a.cp_n(ord('B'))
    a.jr('fresh', 'nz')
    a.ld_a_nn(0xA002)
    a.cp_n(ord('S'))
    a.jr('fresh', 'nz')
    a.ld_a_nn(0xA003)
    a.cp_n(ord('1'))
    a.jr('fresh', 'nz')
    a.ld_a_nn(0xA004)                     # restore digits
    a.ld_nn_a(0xC010)
    a.ld_a_nn(0xA005)
    a.ld_nn_a(0xC011)
    a.ld_a_nn(0xA006)
    a.ld_nn_a(0xC012)
    a.jr('go')
    a.label('fresh')
    for i, ch in enumerate('GBS1'):
        a.ld_r_n('a', ord(ch))
        a.ld_nn_a(0xA000 + i)
    for i in range(3):
        a.xor_a()
        a.ld_nn_a(0xA004 + i)
    a.label('go')
    a.ld_r_n('a', 0x93)                   # LCD on, BG on, OBJ on, tile data 8000, map 9800
    a.ldh_n_a(0x40)

    a.label('main')
    a.call('wait_vbl')
    a.call('draw')
    a.call('input')
    a.label('novbl')
    a.ldh_a_n(0x44)
    a.cp_n(144)
    a.jr('novbl', 'nc')
    a.jr('main')

    a.label('wait_vbl')
    a.ldh_a_n(0x44)
    a.cp_n(144)
    a.jr('wait_vbl', 'nz')
    a.ret()

    a.label('copy')                       # hl -> de, bc bytes
    a.ld_a_hlp()
    a.ld_de_a()
    a.inc_de()
    a.dec_bc()
    a.ld_r_r('a', 'b')
    a.or_r('c')
    a.jr('copy', 'nz')
    a.ret()

    a.label('copymap')                    # 18 rows of 20 bytes from hl to de (32 byte stride)
    a.ld_r_n('b', 18)
    a.label('cm_row')
    a.ld_r_n('c', 20)
    a.label('cm_col')
    a.ld_a_hlp()
    a.ld_de_a()
    a.inc_de()
    a.dec_r('c')
    a.jr('cm_col', 'nz')
    a.push_bc()
    a.ld_r_n('b', 12)                     # de += 12 (rest of the 32 tile row)
    a.label('cm_skip')
    a.inc_de()
    a.dec_r('b')
    a.jr('cm_skip', 'nz')
    a.pop_bc()
    a.dec_r('b')
    a.jr('cm_row', 'nz')
    a.ret()

    a.label('draw')
    a.ld_hl_nn(0xC010)
    a.ld_de_nn(0x9968)                    # row 11, col 8
    for _ in range(3):
        a.ld_a_hlp()
        a.add_a_n(TILE_DIGIT)
        a.ld_de_a()
        a.inc_de()
    a.ld_a_nn(0xC001)
    a.ld_r_r('c', 'a')
    a.ld_r_n('b', 8)
    a.ld_de_nn(0x99A6)                    # row 13, col 6: Start, Select, B, A, Down, Up, Left, Right
    a.label('box')
    a.rlc_r('c')
    a.ld_r_n('a', TILE_SOLID1)
    a.jr('boxs', 'nc')
    a.ld_r_n('a', TILE_SOLID3)
    a.label('boxs')
    a.ld_de_a()
    a.inc_de()
    a.dec_r('b')
    a.jr('box', 'nz')
    a.ld_a_nn(0xC023)                     # RTC latched indicator (row 15, col 9)
    a.or_r('a')
    a.ld_r_n('a', TILE_SOLID1)
    a.jr('rtcs', 'z')
    a.ld_r_n('a', TILE_SOLID3)
    a.label('rtcs')
    a.ld_nn_a(0x99E9)                     # 9800 + 15*32 + 9
    a.ret()

    a.label('input')
    a.ld_r_n('a', 0x20)                   # select directions
    a.ldh_n_a(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.cpl()
    a.and_n(0x0F)
    a.ld_r_r('b', 'a')
    a.ld_r_n('a', 0x10)                   # select buttons
    a.ldh_n_a(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.cpl()
    a.and_n(0x0F)
    a.swap_a()
    a.or_r('b')
    a.ld_r_r('b', 'a')                    # b = joypad now
    a.ld_r_n('a', 0x30)
    a.ldh_n_a(0x00)
    a.ld_a_nn(0xC001)
    a.ld_nn_a(0xC002)                     # previous
    a.ld_r_r('a', 'b')
    a.ld_nn_a(0xC001)
    # frame counter
    a.ld_hl_nn(0xC003)
    a.inc_r('hl')
    a.jr('fc_done', 'nz')
    a.inc_hl16()
    a.inc_r('hl')
    a.label('fc_done')
    # new presses = now & ~prev
    a.ld_a_nn(0xC002)
    a.cpl()
    a.and_r('b')
    a.ld_r_r('c', 'a')                    # c = new presses
    a.ld_r_r('a', 'c')
    a.and_n(0x10)
    a.jr('no_a', 'z')
    # BCD increment of C010..C012
    a.ld_hl_nn(0xC012)
    a.inc_r('hl')
    a.ld_r_r('a', 'hl')
    a.cp_n(10)
    a.jr('bcd_done', 'nz')
    a.xor_a()
    a.ld_r_r('hl', 'a')
    a.dec_hl16()
    a.inc_r('hl')
    a.ld_r_r('a', 'hl')
    a.cp_n(10)
    a.jr('bcd_done', 'nz')
    a.xor_a()
    a.ld_r_r('hl', 'a')
    a.dec_hl16()
    a.inc_r('hl')
    a.ld_r_r('a', 'hl')
    a.cp_n(10)
    a.jr('bcd_done', 'nz')
    a.xor_a()
    a.ld_r_r('hl', 'a')
    a.label('bcd_done')
    a.ld_a_nn(0xC010)
    a.ld_nn_a(0xA004)
    a.ld_a_nn(0xC011)
    a.ld_nn_a(0xA005)
    a.ld_a_nn(0xC012)
    a.ld_nn_a(0xA006)
    a.label('no_a')
    if rtc:
        a.ld_r_r('a', 'c')
        a.and_n(0x80)
        a.jr('no_start', 'z')
        a.xor_a()
        a.ld_nn_a(0x6000)
        a.ld_r_n('a', 1)
        a.ld_nn_a(0x6000)                 # latch
        for i, reg in enumerate((0x08, 0x09, 0x0A)):
            a.ld_r_n('a', reg)
            a.ld_nn_a(0x4000)
            a.ld_a_nn(0xA000)
            a.ld_nn_a(0xC020 + i)
        a.xor_a()
        a.ld_nn_a(0x4000)                 # back to RAM bank 0
        a.ld_r_n('a', 1)
        a.ld_nn_a(0xC023)
        a.label('no_start')
    a.ret()

    # data
    a.label('tiles')
    a.code += tiles
    a.label('map')
    a.code += tmap
    if cgb:
        a.label('attr')
        a.code += make_attr()
        a.label('pal')
        a.code += cgb_palettes()
    return a


LOGO = bytes.fromhex(
    'CEED6666CC0D000B03730083000C000D0008111F8889000EDCCC6EE6DDDDD999BBBB67636E0EECCCDDDC999FBBB9333E')


def build_rom(path, cgb, mbc, rtc, sgb, label, title, mfr=b'\0\0\0\0'):
    a = build_program(cgb, rtc, label)
    prog = a.resolve()
    rom = bytearray(0x8000)
    rom[0x100:0x104] = bytes((0x00, 0xC3, 0x50, 0x01))
    rom[0x104:0x134] = LOGO
    t = title.encode('ascii')[:11]
    rom[0x134:0x134 + len(t)] = t
    rom[0x13F:0x143] = mfr
    rom[0x143] = 0xC0 if cgb else 0x00
    rom[0x146] = 0x03 if sgb else 0x00
    rom[0x147] = mbc
    rom[0x148] = 0x00                     # 32 KiB
    rom[0x149] = 0x02                     # 8 KiB RAM
    rom[0x14A] = 0x01
    rom[0x14B] = 0x33 if sgb else 0x00
    rom[0x150:0x150 + len(prog)] = prog
    assert 0x150 + len(prog) <= 0x8000
    x = 0
    for i in range(0x134, 0x14D):
        x = (x - rom[i] - 1) & 0xFF
    rom[0x14D] = x
    s = (sum(rom) - rom[0x14E] - rom[0x14F]) & 0xFFFF
    rom[0x14E] = s >> 8
    rom[0x14F] = s & 0xFF
    with open(path, 'wb') as f:
        f.write(rom)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(out, exist_ok=True)
    build_rom(os.path.join(out, 'gb_dmg.gb'), cgb=False, mbc=0x10, rtc=True, sgb=False, label='DMG', title='GBTESTDMG')
    build_rom(os.path.join(out, 'gb_cgb.gbc'), cgb=True, mbc=0x1B, rtc=False, sgb=False, label='CGB', title='GBTESTCGB',
              mfr=b'GBCT')
    build_rom(os.path.join(out, 'gb_sgb.gb'), cgb=False, mbc=0x13, rtc=False, sgb=True, label='SGB', title='GBTESTSGB')


if __name__ == '__main__':
    main()
