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


def build_rom(asm, title):
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
    rom += b"\xFF" * (-len(rom) % 0x200)
    return bytes(rom)


VARIANTS = {
    "n32_master": lambda: normal_rom(32, True),
    "n32_slave": lambda: normal_rom(32, False),
    "n8_master": lambda: normal_rom(8, True),
    "n8_slave": lambda: normal_rom(8, False),
    "multi": multi_rom,
    "n32_master_irq": n32_master_irq_rom,
}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for name, fn in VARIANTS.items():
        data = build_rom(fn(), "LINK " + name[:7].upper())
        with open(os.path.join(out, f"link_{name}.gba"), "wb") as f:
            f.write(data)
        print(f"wrote link_{name}.gba ({len(data)} bytes)")


if __name__ == "__main__":
    main()
