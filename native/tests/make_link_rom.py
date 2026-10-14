#!/usr/bin/env python3
"""Hand-assembles tiny GBA test ROMs that exercise the serial port (link cable).

Every ROM logs its transfers into EWRAM so the C test can read them through the bus:

  0x02000000  u32  number of completed transfers
  0x020000F0  u32  base value this player sends (data sent in transfer k = base + k); set by the test
  0x020000F4  u32  start gate: masters wait until this is non-zero (set by the test)
  0x02000100  log, 16 bytes per transfer, entry k:
      Normal modes:  +0 u32 sent, +4 u32 received
      Multi mode:    +0 u16 SIOMULTI0, +2 SIOMULTI1, +4 SIOMULTI2, +6 SIOMULTI3,
                     +8 u16 SIOCNT (read in the IRQ handler), +10 u16 SIOMLT_SEND (what we sent)

ROM variants (file name -> behaviour):
  n32_master  Normal 32-bit, internal clock, polls the start bit
  n32_slave   Normal 32-bit, external clock, polls the start bit
  n8_master / n8_slave   same with 8-bit transfers (data = (base + k) & 0xFF)
  multi       Multiplayer mode, IRQ driven; whoever is player 0 (SIOCNT bit 2 clear) starts
              every transfer from the SIO interrupt handler.
  n32_master_irq  like n32_master but completion handled by the SIO IRQ
  gen3        Gen 3 style multiplayer (see gen3_rom): transfers started from a Timer 3 IRQ only when SIOCNT shows
              SD=1 (everybody ready) and SI=0 (master); SIOCNT SD/SI/ID/error bits logged, same ROM for every role.
  pokesim     "Pokemon-like" Multiplayer traffic (see pokesim_rom): one exchange per vblank, sequence + checksum
              validation, good/bad/missed counters in EWRAM and on screen. Same ROM for every role.
"""
import os
import struct
import sys

R = {f"r{i}": i for i in range(16)}
R.update({"sp": 13, "lr": 14, "pc": 15})
IO = 0x04000100   # base register for the serial block (halfword offsets are limited to 255)
IOINT = 0x04000200  # IE / IF / IME
EWRAM = 0x02000000


class Asm:
    def __init__(self):
        self.words = []       # ints or ('b', cond, label) / ('ldr', rd, key)
        self.labels = {}
        self.pool = []        # constants or ('label', name)
        self.pool_index = {}

    # --- infrastructure ---
    def label(self, name):
        self.labels[name] = len(self.words)

    def emit(self, w):
        self.words.append(w & 0xFFFFFFFF)

    @staticmethod
    def encode_imm(v):
        v &= 0xFFFFFFFF
        for rot in range(0, 32, 2):
            r = ((v << rot) | (v >> (32 - rot))) & 0xFFFFFFFF if rot else v
            if r < 256:
                return (rot // 2) << 8 | r
        raise ValueError(f"cannot encode immediate {v:#x}")

    # --- data processing ---
    def _dp(self, op, rd, rn, imm, s=0):
        self.emit(0xE2000000 | op << 21 | s << 20 | R[rn] << 16 | R[rd] << 12 | self.encode_imm(imm))

    def mov(self, rd, imm):      self._dp(0xD, rd, "r0", imm)
    def add(self, rd, rn, imm):  self._dp(0x4, rd, rn, imm)
    def sub(self, rd, rn, imm):  self._dp(0x2, rd, rn, imm)
    def orr(self, rd, rn, imm):  self._dp(0xC, rd, rn, imm)
    def and_(self, rd, rn, imm): self._dp(0x0, rd, rn, imm)
    def tst(self, rn, imm):      self._dp(0x8, "r0", rn, imm, s=1)
    def cmp(self, rn, imm):      self._dp(0xA, "r0", rn, imm, s=1)

    def mov_reg(self, rd, rm):
        self.emit(0xE1A00000 | R[rd] << 12 | R[rm])

    def lsl(self, rd, rm, n):
        self.emit(0xE1A00000 | R[rd] << 12 | n << 7 | R[rm])

    def add_reg(self, rd, rn, rm):
        self.emit(0xE0800000 | R[rn] << 16 | R[rd] << 12 | R[rm])

    # --- memory ---
    def _ldst(self, load, byte, rd, rn, off):
        u = 0x00800000 if off >= 0 else 0
        self.emit(0xE5000000 | u | (0x00400000 if byte else 0) | (0x00100000 if load else 0)
                  | R[rn] << 16 | R[rd] << 12 | abs(off))

    def ldr(self, rd, rn, off=0):  self._ldst(1, 0, rd, rn, off)
    def str(self, rd, rn, off=0):  self._ldst(0, 0, rd, rn, off)

    def _ldsth(self, load, rd, rn, off):
        assert 0 <= off < 256
        self.emit(0xE1C000B0 | (0x00100000 if load else 0) | R[rn] << 16 | R[rd] << 12
                  | (off >> 4) << 8 | (off & 0xF))

    def ldrh(self, rd, rn, off=0): self._ldsth(1, rd, rn, off)
    def strh(self, rd, rn, off=0): self._ldsth(0, rd, rn, off)

    def ldr_const(self, rd, value):
        """ldr rd, =value  (value: int or ('label', name))"""
        key = value
        if key not in self.pool_index:
            self.pool_index[key] = len(self.pool)
            self.pool.append(value)
        self.words.append(('ldr', R[rd], key))

    # --- branches ---
    def b(self, label, cond=0xE):
        self.words.append(('b', cond, label))

    def beq(self, label): self.b(label, 0x0)
    def bne(self, label): self.b(label, 0x1)

    def bx_lr(self):
        self.emit(0xE12FFF1E)

    # --- assemble ---
    def assemble(self, base_off):
        """Resolve at ROM offset base_off (words[0] lives there). Returns bytes."""
        n = len(self.words)
        pool_start = n  # pool follows code
        out = []
        for i, w in enumerate(self.words):
            pc = base_off + i * 4
            if isinstance(w, tuple):
                if w[0] == 'b':
                    _, cond, lab = w
                    target = base_off + self.labels[lab] * 4
                    off = (target - (pc + 8)) >> 2
                    out.append(cond << 28 | 0x0A000000 | (off & 0xFFFFFF))
                else:
                    _, rd, key = w
                    target = base_off + (pool_start + self.pool_index[key]) * 4
                    off = target - (pc + 8)
                    assert 0 <= off < 4096
                    out.append(0xE59F0000 | rd << 12 | off)
            else:
                out.append(w)
        for v in self.pool:
            if isinstance(v, tuple):
                out.append(0x08000000 + base_off + self.labels[v[1]] * 4)
            else:
                out.append(v & 0xFFFFFFFF)
        return struct.pack(f"<{len(out)}I", *out)


def log_and_count(a):
    """Normal-mode epilogue: r2 = sent, r5 = received, r4 = counter, r9 = log pointer, r6 = EWRAM."""
    a.str("r2", "r9", 0)
    a.str("r5", "r9", 4)
    a.add("r9", "r9", 16)
    a.add("r4", "r4", 1)
    a.str("r4", "r6", 0)


def normal_rom(bits, master):
    a = Asm()
    a.label("main")
    a.ldr_const("r0", IO)
    a.mov("r1", 0)
    a.strh("r1", "r0", 0x34)                 # RCNT = 0 (mode selected by SIOCNT)
    a.ldr_const("r6", EWRAM)
    a.add("r9", "r6", 0x100)
    a.mov("r4", 0)
    if master:
        a.label("gate")                       # wait for the test to open the gate
        a.ldr("r1", "r6", 0xF4)
        a.cmp("r1", 0)
        a.beq("gate")
    a.label("loop")
    a.ldr("r7", "r6", 0xF0)
    a.add_reg("r2", "r7", "r4")
    if bits == 32:
        a.str("r2", "r0", 0x20)              # SIODATA32
        ctl = 0x1083 if master else 0x1080
    else:
        a.and_("r2", "r2", 0xFF)
        a.strh("r2", "r0", 0x2A)             # SIODATA8
        ctl = 0x0083 if master else 0x0080
    a.ldr_const("r3", ctl)
    a.strh("r3", "r0", 0x28)                 # SIOCNT: start (master: internal clock; slave: armed)
    a.label("wait")
    a.ldrh("r3", "r0", 0x28)
    a.tst("r3", 0x80)
    a.bne("wait")
    if bits == 32:
        a.ldr("r5", "r0", 0x20)
    else:
        a.ldrh("r5", "r0", 0x2A)
    log_and_count(a)
    a.b("loop")
    return a


def multi_rom():
    a = Asm()
    a.label("main")
    a.ldr_const("r0", IO)
    a.ldr_const("r6", EWRAM)
    a.ldr_const("r1", 0x03007FFC)
    a.ldr_const("r2", ('label', "handler"))
    a.str("r2", "r1", 0)                      # IRQ vector
    a.mov("r1", 0)
    a.strh("r1", "r0", 0x34)                 # RCNT = 0
    a.ldr_const("r1", 0x6003)                 # multiplayer, IRQ enable, 115200 baud
    a.strh("r1", "r0", 0x28)
    a.ldr("r7", "r6", 0xF0)
    a.strh("r7", "r0", 0x2A)                 # SIOMLT_SEND = base + 0
    a.ldr_const("r8", IOINT)
    a.mov("r1", 0x80)
    a.strh("r1", "r8", 0)                     # IE = SIO
    a.mov("r1", 1)
    a.strh("r1", "r8", 8)                     # IME = 1
    a.label("gate")
    a.ldr("r1", "r6", 0xF4)
    a.cmp("r1", 0)
    a.beq("gate")
    a.ldrh("r1", "r0", 0x28)
    a.tst("r1", 4)                            # SI/slave bit: children never start transfers
    a.bne("idle")
    a.orr("r1", "r1", 0x80)
    a.strh("r1", "r0", 0x28)                 # master: start first transfer
    a.label("idle")
    a.b("idle")

    a.label("handler")                        # entered through the BIOS IRQ stub (r0-r3, r12 saved)
    a.ldr_const("r0", IO)
    a.ldr_const("r1", IOINT)
    a.mov("r2", 0x80)
    a.strh("r2", "r1", 2)                     # acknowledge IF.SIO
    a.ldr_const("r3", EWRAM)
    a.ldr("r1", "r3", 0)                      # completed transfers
    a.lsl("r2", "r1", 4)
    a.add_reg("r2", "r2", "r3")
    a.add("r2", "r2", 0x100)                  # log entry
    for i in range(4):
        a.ldrh("r12", "r0", 0x20 + 2 * i)
        a.strh("r12", "r2", 2 * i)
    a.ldrh("r12", "r0", 0x28)
    a.strh("r12", "r2", 8)
    a.ldrh("r12", "r0", 0x2A)
    a.strh("r12", "r2", 10)
    a.add("r1", "r1", 1)
    a.str("r1", "r3", 0)
    a.ldr("r12", "r3", 0xF0)
    a.add_reg("r12", "r12", "r1")
    a.strh("r12", "r0", 0x2A)                # next value to send
    a.ldrh("r12", "r0", 0x28)
    a.tst("r12", 4)
    a.bne("done")
    a.orr("r12", "r12", 0x80)
    a.strh("r12", "r0", 0x28)                # master restarts immediately
    a.label("done")
    a.bx_lr()
    return a


def n32_master_irq_rom():
    """Normal-32 master, completion through the SIO interrupt (checks IRQ raising in Normal mode)."""
    a = Asm()
    a.label("main")
    a.ldr_const("r0", IO)
    a.ldr_const("r6", EWRAM)
    a.ldr_const("r1", 0x03007FFC)
    a.ldr_const("r2", ('label', "handler"))
    a.str("r2", "r1", 0)
    a.mov("r1", 0)
    a.strh("r1", "r0", 0x34)
    a.ldr_const("r8", IOINT)
    a.mov("r1", 0x80)
    a.strh("r1", "r8", 0)
    a.mov("r1", 1)
    a.strh("r1", "r8", 8)
    a.label("gate")
    a.ldr("r1", "r6", 0xF4)
    a.cmp("r1", 0)
    a.beq("gate")
    a.ldr("r2", "r6", 0xF0)
    a.str("r2", "r0", 0x20)
    a.ldr_const("r3", 0x5083)                 # 32-bit, IRQ, start, internal clock, 2 MHz
    a.strh("r3", "r0", 0x28)
    a.label("idle")
    a.b("idle")

    a.label("handler")
    a.ldr_const("r0", IO)
    a.ldr_const("r1", IOINT)
    a.mov("r2", 0x80)
    a.strh("r2", "r1", 2)
    a.ldr_const("r3", EWRAM)
    a.ldr("r1", "r3", 0)
    a.lsl("r2", "r1", 4)
    a.add_reg("r2", "r2", "r3")
    a.add("r2", "r2", 0x100)
    a.ldr("r12", "r3", 0xF0)
    a.add_reg("r12", "r12", "r1")
    a.str("r12", "r2", 0)                     # sent
    a.ldr("r12", "r0", 0x20)
    a.str("r12", "r2", 4)                     # received
    a.add("r1", "r1", 1)
    a.str("r1", "r3", 0)
    a.ldr("r12", "r3", 0xF0)
    a.add_reg("r12", "r12", "r1")
    a.str("r12", "r0", 0x20)
    a.ldr_const("r12", 0x5083)
    a.strh("r12", "r0", 0x28)
    a.bx_lr()
    return a


def gen3_rom():
    """Gen 3 (Pokemon FRLG/Emerald) style multiplayer: the role comes from the SIOCNT terminals (SD bit 3, SI bit 2),
    the master starts a transfer from a Timer 3 interrupt only when SD = 1 ("all GBAs ready") and the port is idle, the
    SIO interrupt reads SIOMULTI0-3 and checks the ID / error bits and loads the next word to send.
    EWRAM: 00 completed transfers, 08 timer IRQs, 0C master wanted to start but SD was 0, 10 transfers started,
    14 SIOCNT error bit (6) seen in the SIO IRQ, 18 SIOCNT busy bit seen set in the SIO IRQ (must stay 0),
    E0 last SIOCNT seen by the timer, E4 SIOCNT seen by the timer right before the last start,
    F0 base value, F4 gate (master waits until the test sets it). Log entry k (16 bytes at 100 + 16k):
    SIOMULTI0-3, SIOCNT in the IRQ (+8), SIOMLT_SEND of this transfer (+10), SIOCNT the timer saw before it (+12)."""
    a = Asm()
    E = lambda w: a.emit(w)

    def inc(off, tmp="r8"):
        a.ldr(tmp, "r5", off); a.add(tmp, tmp, 1); a.str(tmp, "r5", off)

    a.label("main")
    a.ldr_const("r4", IO)
    a.ldr_const("r5", EWRAM)
    a.ldr_const("r1", 0x03007FFC)
    a.ldr_const("r2", ('label', "handler"))
    a.str("r2", "r1", 0)                      # IRQ vector
    a.mov("r1", 0)
    a.strh("r1", "r4", 0x34)                  # RCNT = 0
    a.ldr_const("r1", 0x6003)                 # multiplayer, IRQ enable, 115200 baud
    a.strh("r1", "r4", 0x28)
    a.ldr("r7", "r5", 0xF0)
    a.strh("r7", "r4", 0x2A)                  # SIOMLT_SEND = base + 0
    a.ldr_const("r1", 0xF000)                 # Timer 3 reload: overflows every 4096 cycles
    a.strh("r1", "r4", 0x0C)
    a.ldr_const("r1", 0x00C0)                 # enable + IRQ, prescaler 1
    a.strh("r1", "r4", 0x0E)
    a.ldr_const("r8", IOINT)
    a.mov("r1", 0xC0)
    a.strh("r1", "r8", 0)                     # IE = SIO | timer 3
    a.mov("r1", 1)
    a.strh("r1", "r8", 8)                     # IME
    a.label("idle")
    a.b("idle")

    a.label("handler")
    E(0xE92D43F0)                             # stmfd sp!,{r4-r9,lr}
    a.ldr_const("r0", IOINT)
    a.ldr_const("r4", IO)
    a.ldr_const("r5", EWRAM)
    a.ldrh("r1", "r0", 2)
    a.and_("r2", "r1", 0xC0)
    a.strh("r2", "r0", 2)                     # acknowledge what we saw
    a.tst("r1", 0x80)
    a.beq("no_sio")
    a.ldr("r3", "r5", 0)
    a.lsl("r2", "r3", 4)
    a.add_reg("r2", "r2", "r5")
    a.add("r2", "r2", 0x100)
    for i in range(4):
        a.ldrh("r12", "r4", 0x20 + 2 * i)
        a.strh("r12", "r2", 2 * i)
    a.ldrh("r12", "r4", 0x28)
    a.strh("r12", "r2", 8)
    a.tst("r12", 0x40)
    a.beq("noerr")
    inc(0x14)
    a.label("noerr")
    a.ldrh("r12", "r4", 0x28)
    a.tst("r12", 0x80)
    a.beq("nobusy")
    inc(0x18)
    a.label("nobusy")
    a.ldrh("r12", "r4", 0x2A)
    a.strh("r12", "r2", 10)
    a.ldr("r12", "r5", 0xE4)
    a.strh("r12", "r2", 12)
    a.add("r3", "r3", 1)
    a.str("r3", "r5", 0)
    a.ldr("r12", "r5", 0xF0)
    a.add_reg("r12", "r12", "r3")
    a.strh("r12", "r4", 0x2A)                 # next word to send
    a.label("no_sio")
    a.tst("r1", 0x40)
    a.beq("out")
    inc(0x08)
    a.ldrh("r6", "r4", 0x28)
    a.str("r6", "r5", 0xE0)
    a.and_("r7", "r6", 0xC)
    a.cmp("r7", 0x8)                          # terminals: SD set, SI clear = master with everybody ready
    a.bne("not_ready")
    a.ldr("r8", "r5", 0xF4)
    a.cmp("r8", 0)
    a.beq("out")
    a.tst("r6", 0x80)
    a.bne("out")
    a.ldr("r8", "r5", 0x10)                   # only after the SIO IRQ of the previous transfer has been handled
    a.ldr("r9", "r5", 0)
    E(0xE1580009)                             # cmp r8,r9
    a.bne("out")
    a.str("r6", "r5", 0xE4)
    a.orr("r6", "r6", 0x80)
    a.strh("r6", "r4", 0x28)
    inc(0x10)
    a.b("out")
    a.label("not_ready")
    a.tst("r6", 4)
    a.bne("out")                              # a child never starts
    a.ldr("r8", "r5", 0xF4)
    a.cmp("r8", 0)
    a.beq("out")
    inc(0x0C)
    a.label("out")
    E(0xE8BD43F0)
    a.bx_lr()
    return a


# ---------------------------------------------------------------------------------------------------------
# pokesim: mimics Gen 3 link behaviour (one multi-play exchange per frame, driven from the vblank handler)
#
# Protocol (16-bit word in SIOMLT_SEND): bits 0-11 = sequence number (incremented every vblank),
# bits 12-15 = checksum nibble = (s ^ s>>4 ^ s>>8 ^ 0xA) & 0xF with s = the 12-bit sequence.
# Master (SIOCNT bit 2 clear) starts one transfer per vblank (after a 3 frame warm-up) and validates
# SIOMULTI1; children validate SIOMULTI0 from the SIO IRQ. A word is "good" if the checksum matches and the
# sequence advanced by 0..3 (mod 4096) since the last good one, "bad" otherwise, 0xFFFF = "missed" (no partner).
# A vblank that saw no completed exchange since the previous vblank also counts as "missed".
# If no good exchange arrives for 60 frames the error flag (EWRAM+0x18) is raised (backdrop turns red, E shown).
#
# EWRAM (u32 each): 00 good, 04 bad, 08 missed, 0C my seq, 10 last partner seq (0xFFFFFFFF = none yet),
#   14 frames (vblanks), 18 error flag, 1C error count, 20 dup (delta 0), 24 skip (delta >= 2),
#   28 frames since last good, 2C exchanges since last vblank, 30 max gap (frames), 34 total SIO IRQs,
#   38 role (1 = master), 40 last partner word, 44 SIOCNT at last SIO IRQ, 3C rolling hash of (KEYINPUT, partner words) so inputs and link data are visible in state.
# Screen (mode 0, BG0): G/B/M rows show good/bad/missed as 8 digits.
# ---------------------------------------------------------------------------------------------------------
GLYPHS = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
}
TILE_ORDER = [" "] + list("0123456789") + ["G", "B", "M", "E"]  # tile 0 = blank, digit d = tile d+1, G=11 B=12 M=13 E=14
POKESIM_FONT_OFF = 0x1000


def font_tiles():
    out = bytearray()
    for ch in TILE_ORDER:
        rows = GLYPHS.get(ch, ["00000"] * 7)
        for y in range(8):
            px = [0] * 8
            if y < 7:
                for x in range(5):
                    if rows[y][x] == "1":
                        px[x + 1] = 1
            for i in range(4):
                out.append(px[2 * i] | (px[2 * i + 1] << 4))
    return bytes(out)


def _alu_reg(op, rd, rn, rm, shift=0, kind=0, s=0):
    return 0xE0000000 | op << 21 | s << 20 | R[rn] << 16 | R[rd] << 12 | shift << 7 | kind << 5 | R[rm]


def pokesim_rom():
    a = Asm()
    E = lambda w: a.emit(w)
    LSL, LSR = 0, 1

    def inc(off, base="r5", tmp="r6"):
        a.ldr(tmp, base, off); a.add(tmp, tmp, 1); a.str(tmp, base, off)

    def seq12(dst, src):                      # dst = src & 0xFFF
        E(0xE1A00000 | R[dst] << 12 | 20 << 7 | R[src])
        E(0xE1A00020 | R[dst] << 12 | 20 << 7 | R[dst])

    def checksum(dst, s12):                   # dst = (s ^ s>>4 ^ s>>8 ^ 0xA) & 0xF
        E(_alu_reg(0x1, dst, s12, s12, 4, LSR))
        E(_alu_reg(0x1, dst, dst, s12, 8, LSR))
        a._dp(0x1, dst, dst, 0xA)
        a.and_(dst, dst, 0xF)

    # ------------------------------------------------------------------ init
    a.label("main")
    a.ldr_const("r0", 0x04000000)
    a.ldr_const("r1", 0x0100); a.strh("r1", "r0", 0)       # DISPCNT: mode 0, BG0
    a.ldr_const("r1", 0x1F00); a.strh("r1", "r0", 8)       # BG0CNT: charblock 0, screenblock 31
    a.ldr_const("r2", 0x05000000)
    a.ldr_const("r1", 0x4000); a.strh("r1", "r2", 0)       # backdrop: dark blue
    a.ldr_const("r1", 0x7FFF); a.strh("r1", "r2", 2)       # colour 1: white
    a.ldr_const("r1", 0x08000000 + POKESIM_FONT_OFF)
    a.ldr_const("r2", 0x06000000)
    a.mov("r3", len(TILE_ORDER) * 8)
    a.label("cp")
    E(0xE4900004 | R["r1"] << 16 | R["r7"] << 12)           # ldr r7,[r1],#4
    E(0xE4800004 | R["r2"] << 16 | R["r7"] << 12)           # str r7,[r2],#4
    a._dp(0x2, "r3", "r3", 1, s=1)                            # subs r3,r3,#1
    a.bne("cp")
    for row, tile in ((4, 11), (6, 12), (8, 13)):
        a.ldr_const("r2", 0x0600F800 + row * 64)
        a.mov("r1", tile); a.strh("r1", "r2", 12)             # label letter at column 6
    a.ldr_const("r5", EWRAM)
    a._dp(0xF, "r1", "r0", 0); a.str("r1", "r5", 0x10)       # last partner seq = 0xFFFFFFFF
    a.ldr_const("r1", 0x03007FFC)
    a.ldr_const("r2", ('label', "handler"))
    a.str("r2", "r1", 0)                                      # IRQ vector
    a.ldr_const("r4", IO)
    a.mov("r1", 0); a.strh("r1", "r4", 0x34)                  # RCNT = 0
    a.ldr_const("r1", 0x6003); a.strh("r1", "r4", 0x28)       # multiplayer, IRQ enable, 115200
    a.ldr_const("r0", IOINT)
    a.mov("r1", 8); a.ldr_const("r2", 0x04000004); a.strh("r1", "r2", 0)   # DISPSTAT: vblank IRQ
    a.mov("r1", 0x81); a.strh("r1", "r0", 0)                  # IE = vblank | serial
    a.mov("r1", 1); a.strh("r1", "r0", 8)                     # IME
    a.label("idle")
    a.b("idle")

    # ------------------------------------------------------------------ interrupt handler
    a.label("handler")
    E(0xE92D43F0)                                             # stmfd sp!,{r4-r9,lr}
    a.ldr_const("r0", IOINT)
    a.ldr_const("r4", IO)
    a.ldr_const("r5", EWRAM)
    # ---- serial first
    a.ldrh("r1", "r0", 2)
    a.tst("r1", 0x80)
    a.beq("no_sio")
    a.mov("r2", 0x80); a.strh("r2", "r0", 2)
    inc(0x2C); inc(0x34)
    a.ldrh("r6", "r4", 0x28)
    a.tst("r6", 4); a.bne("sio_child")
    a.ldrh("r2", "r4", 0x22); a.b("sio_chk")
    a.label("sio_child")
    a.ldrh("r2", "r4", 0x20)
    a.label("sio_chk")
    a.str("r2", "r5", 0x40); a.str("r6", "r5", 0x44)           # debug: last partner word and SIOCNT seen in the IRQ
    a.ldr_const("r3", 0xFFFF); E(0xE1520003); a.bne("sio_have")
    inc(0x08); a.b("sio_end")
    a.label("sio_have")
    seq12("r1", "r2")
    checksum("r3", "r1")
    E(0xE1A00020 | R["r7"] << 12 | 12 << 7 | R["r2"])         # r7 = word >> 12
    E(0xE1530007); a.bne("sio_bad")                           # cmp r3,r7
    a.ldr("r8", "r5", 0x10)
    a._dp(0xB, "r0", "r8", 1, s=1)                             # cmn r8,#1   (no good word yet)
    a.beq("sio_good")
    E(_alu_reg(0x2, "r9", "r1", "r8"))                        # delta = seq - last
    E(0xE1A00000 | R["r9"] << 12 | 20 << 7 | R["r9"]); E(0xE1A00020 | R["r9"] << 12 | 20 << 7 | R["r9"])
    a.cmp("r9", 3); a.b("sio_bad", 0x8)                        # bhi
    a.cmp("r9", 0); a.bne("nodup"); inc(0x20)
    a.label("nodup")
    a.cmp("r9", 2); a.b("sio_good", 0x3)
    inc(0x24)
    a.label("sio_good")
    inc(0x00)
    a.str("r1", "r5", 0x10)
    a.mov("r6", 0); a.str("r6", "r5", 0x28); a.str("r6", "r5", 0x18)
    a.ldr("r6", "r5", 0x3C); E(_alu_reg(0x4, "r6", "r6", "r6", 5, LSL)); E(_alu_reg(0x4, "r6", "r6", "r2"))
    a.str("r6", "r5", 0x3C)
    a.b("sio_end")
    a.label("sio_bad")
    inc(0x04)
    a.label("sio_end")
    a.label("no_sio")
    a.ldrh("r1", "r0", 2)
    a.tst("r1", 1)
    a.beq("out")

    # ---- vblank (after the serial handler so a pending transfer result is read before anything else)
    a.mov("r2", 1); a.strh("r2", "r0", 2)
    inc(0x14, tmp="r1"); a.ldr("r1", "r5", 0x14)             # r1 = frames
    a.ldr_const("r2", 0x04000130); a.ldrh("r2", "r2", 0)
    a.ldr_const("r3", 0x3FF); E(_alu_reg(0x1, "r2", "r2", "r3"))   # keys, active high
    a.ldr("r3", "r5", 0x3C); E(_alu_reg(0x4, "r3", "r3", "r3", 5, LSL)); E(_alu_reg(0x4, "r3", "r3", "r2"))
    a.str("r3", "r5", 0x3C)
    a.ldrh("r6", "r4", 0x28)                                   # SIOCNT
    a.cmp("r1", 8); a.b("skip_miss", 0xB)
    a.ldr("r2", "r5", 0x2C); a.cmp("r2", 0); a.bne("skip_miss")
    inc(0x08, tmp="r7")
    a.label("skip_miss")
    a.mov("r2", 0); a.str("r2", "r5", 0x2C)
    a.ldr("r2", "r5", 0x28); a.add("r2", "r2", 1); a.str("r2", "r5", 0x28)   # frames since good
    a.ldr("r3", "r5", 0x30); E(0xE1520003); a.b("nomax", 0x9); a.str("r2", "r5", 0x30)   # cmp r2,r3 ; bls
    a.label("nomax")
    a.cmp("r2", 60); a.b("wd_ok", 0xB)
    a.ldr("r3", "r5", 0x18); a.cmp("r3", 0); a.bne("wd_ok")
    a.mov("r3", 1); a.str("r3", "r5", 0x18)
    inc(0x1C, tmp="r7")
    a.label("wd_ok")
    a.ldr("r1", "r5", 0x0C); a.add("r1", "r1", 1); a.str("r1", "r5", 0x0C)  # my sequence
    seq12("r1", "r1")
    checksum("r3", "r1")
    E(_alu_reg(0xC, "r1", "r1", "r3", 12, LSL))
    a.strh("r1", "r4", 0x2A)                                   # SIOMLT_SEND
    # picture: error state
    a.ldr_const("r8", 0x05000000); a.ldr_const("r9", 0x0600F800 + 10 * 64)
    a.ldr("r1", "r5", 0x18); a.cmp("r1", 0)
    a.mov("r2", 0x4000); a.mov("r3", 0)
    a.beq("ok_col")
    a.mov("r2", 0x1F); a.mov("r3", 14)
    a.label("ok_col")
    a.strh("r2", "r8", 0); a.strh("r3", "r9", 12)
    # picture: counters
    for off, row in ((0x00, 4), (0x04, 6), (0x08, 8)):
        a.ldr("r7", "r5", off)
        a.ldr_const("r9", 0x0600F800 + row * 64 + 16)
        for i in range(8):
            a.mov("r2", 0)
            a.ldr_const("r3", 10 ** (7 - i))
            a.label(f"dl{off}_{i}")
            E(0xE1570003); a.b(f"dd{off}_{i}", 0x3)           # cmp r7,r3 ; blo
            E(_alu_reg(0x2, "r7", "r7", "r3")); a.add("r2", "r2", 1)
            a.b(f"dl{off}_{i}")
            a.label(f"dd{off}_{i}")
            a.add("r2", "r2", 1); a.strh("r2", "r9", 2 * i)
    # master: start this frame's transfer
    a.tst("r6", 4); a.bne("out")
    a.mov("r2", 1); a.str("r2", "r5", 0x38)
    a.ldr("r2", "r5", 0x14); a.cmp("r2", 3); a.b("out", 0xB)
    a.tst("r6", 0x80); a.bne("out")
    a.orr("r6", "r6", 0x80); a.strh("r6", "r4", 0x28)

    a.label("out")
    E(0xE8BD43F0)                                             # ldmfd sp!,{r4-r9,lr}
    a.bx_lr()
    return a


def build_rom(asm, title, extra=None):
    code_off = 0xC0
    body = asm.assemble(code_off)
    rom = bytearray(0x100 + len(body))
    # header: branch to main at 0xC0 (ARM branch at offset 0)
    off = (code_off + asm.labels["main"] * 4 - (0 + 8)) >> 2
    struct.pack_into("<I", rom, 0, 0xEA000000 | (off & 0xFFFFFF))
    rom[0xA0:0xA0 + 12] = title.encode().ljust(12, b"\0")[:12]
    rom[0xAC:0xB0] = b"LNKT"
    rom[0xB0:0xB2] = b"01"
    rom[0xB2] = 0x96
    chk = 0
    for b in rom[0xA0:0xBD]:
        chk = (chk - b) & 0xFF
    rom[0xBD] = (chk - 0x19) & 0xFF
    rom[code_off:code_off + len(body)] = body
    # pad to a multiple of 0x200
    for off, blob in (extra or {}).items():
        assert off >= len(rom), "code overlaps data"
        rom += b"\xFF" * (off - len(rom)) + blob
    rom += b"\xFF" * (-len(rom) % 0x200)
    return bytes(rom)


VARIANTS = {
    "n32_master": lambda: normal_rom(32, True),
    "n32_slave": lambda: normal_rom(32, False),
    "n8_master": lambda: normal_rom(8, True),
    "n8_slave": lambda: normal_rom(8, False),
    "multi": multi_rom,
    "n32_master_irq": n32_master_irq_rom,
    "gen3": gen3_rom,
    "pokesim": pokesim_rom,
}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for name, fn in VARIANTS.items():
        extra = {POKESIM_FONT_OFF: font_tiles()} if name == "pokesim" else None
        data = build_rom(fn(), "LINK " + name[:7].upper(), extra)
        with open(os.path.join(out, f"link_{name}.gba"), "wb") as f:
            f.write(data)
        print(f"wrote link_{name}.gba ({len(data)} bytes)")


if __name__ == "__main__":
    main()
