#!/usr/bin/env python3
"""Generate the Game Boy / Game Boy Color LINK test ROMs (hand assembled LR35902, no tools, nothing downloaded).

    make_gb_link_rom.py <outdir>      writes gblink_dmg.gb, gblink_cgb.gbc, gblink_dmg_b.gb and gblink_auto.gb

The program is a tiny serial-port exerciser. It exchanges bytes over the link cable (SB / SC, serial interrupt) the way
Pokemon Red/Gold do: the slave arms its port (SB = next byte, SC = 0x80) and re-arms inside the interrupt handler, the master
writes SB and SC = 0x81 and waits for the interrupt. Every completed transfer is logged. gblink_dmg_b.gb is the same program with a different
title / header checksum, i.e. a *different ROM* for the classic (different games) link tests; gblink_auto.gb starts in role 3 by itself.

WRAM contract (tests poke / peek these through gba_priv_read16 / gba_priv_write32; all single bytes unless noted):
  C100 role   0 idle, 1 master, 2 slave, 3 slave until the A button is pressed, then master (gblink_auto.gb: the role after boot; the others start idle)
  C102 base   tx byte of transfer k is base + k            C103 interval  frames between master transfers (default 1)
  C119 scval  SC value the master writes (default 0x81; 0x83 = CGB fast clock)
  C110 k      transfers completed                          C111 n  log entries (saturates at 255; 128 are stored)
  C104 frame counter (8 bit)   C115 byte armed / sent for the current transfer   C117 master busy   C118 slave armed
  C200..C2FF  log: entry i is two bytes (sent, received) at C200 + 2 i, i < 128
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_gb_rom import Asm, LOGO  # noqa: E402


class A(Asm):
    def add_a_hl(self):  self.b(0x86)
    def sub_hl(self):    self.b(0x96)
    def cp_hl(self):     self.b(0xBE)
    def add_a_a(self):   self.b(0x87)
    def ei(self):        self.b(0xFB)
    def reti(self):      self.b(0xD9)
    def push_af(self):   self.b(0xF5)
    def pop_af(self):    self.b(0xF1)
    def push_hl(self):   self.b(0xE5)
    def pop_hl(self):    self.b(0xE1)
    def ld_hl_a(self):   self.b(0x77)          # ld (hl),a


def build(default_role):
    a = A(0x150)
    a.di()
    a.ld_sp_nn(0xFFFE)
    # clear C100-C2FF
    a.ld_hl_nn(0xC100)
    a.ld_bc_nn(0x0200)
    a.label('clr')
    a.xor_a()
    a.ld_hlp_a()
    a.dec_bc()
    a.ld_r_r('a', 'b')
    a.or_r('c')
    a.jr('clr', 'nz')
    if default_role:
        a.ld_r_n('a', default_role)
        a.ld_nn_a(0xC100)                 # role after boot (the idle ROMs wait for the tests to set it)
    a.ld_r_n('a', 1)
    a.ld_nn_a(0xC103)                     # interval
    a.ld_r_n('a', 0x81)
    a.ld_nn_a(0xC119)                     # scval
    # sound on (a tone like gb_dmg.gb): mGBA's audio unit only reloads exactly from a state once the game has set it up
    for reg, val in ((0x26, 0x80), (0x25, 0xFF), (0x24, 0x77), (0x11, 0x80), (0x12, 0xF0), (0x13, 0xD6), (0x14, 0x86)):
        a.ld_r_n('a', val)
        a.ldh_n_a(reg)
    a.xor_a()
    a.ldh_n_a(0x0F)                       # IF = 0
    a.ld_r_n('a', 0x08)
    a.ldh_n_a(0xFF)                       # IE = serial
    a.ei()

    a.label('main')
    a.call('wait_vbl')
    a.ld_hl_nn(0xC104)
    a.inc_r('hl')                         # frame counter
    a.ld_a_nn(0xC100)
    a.cp_n(1)
    a.jr('do_master', 'z')
    a.cp_n(2)
    a.jr('do_slave', 'z')
    a.cp_n(3)
    a.jr('do_auto', 'z')
    a.jr('main')

    a.label('do_slave')
    a.call('arm_slave')
    a.jr('main')

    a.label('do_auto')
    a.ld_r_n('a', 0x10)                   # select buttons
    a.ldh_n_a(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ldh_a_n(0x00)
    a.ld_r_r('b', 'a')
    a.ld_r_n('a', 0x30)
    a.ldh_n_a(0x00)
    a.ld_r_r('a', 'b')
    a.and_n(0x01)                         # A: 0 = pressed
    a.jr('auto_slave', 'nz')
    a.ld_r_n('a', 1)
    a.ld_nn_a(0xC100)                     # become master
    a.jr('do_master')
    a.label('auto_slave')
    a.call('arm_slave')
    a.jr('main')

    a.label('do_master')
    a.ld_a_nn(0xC117)
    a.or_r('a')
    a.jr('main', 'nz')                    # transfer in progress
    a.ld_a_nn(0xC104)
    a.ld_hl_nn(0xC105)
    a.sub_hl()                            # frames since the last start
    a.ld_hl_nn(0xC103)
    a.cp_hl()
    a.jr('main', 'c')                     # not yet
    a.ld_a_nn(0xC104)
    a.ld_nn_a(0xC105)
    a.ld_a_nn(0xC110)
    a.ld_hl_nn(0xC102)
    a.add_a_hl()
    a.ld_nn_a(0xC115)
    a.ldh_n_a(0x01)                       # SB = base + k
    a.ld_r_n('a', 1)
    a.ld_nn_a(0xC117)
    a.ld_a_nn(0xC119)
    a.ldh_n_a(0x02)                       # SC = 0x81 (or 0x83)
    a.jr('main')

    a.label('arm_slave')
    a.ld_a_nn(0xC118)
    a.or_r('a')
    a.jr('arm_done', 'nz')
    a.ld_a_nn(0xC110)
    a.ld_hl_nn(0xC102)
    a.add_a_hl()
    a.ld_nn_a(0xC115)
    a.ldh_n_a(0x01)
    a.ld_r_n('a', 1)
    a.ld_nn_a(0xC118)
    a.ld_r_n('a', 0x80)
    a.ldh_n_a(0x02)
    a.label('arm_done')
    a.ret()

    a.label('wait_vbl')
    a.ldh_a_n(0x44)
    a.cp_n(144)
    a.jr('wait_vbl', 'nz')
    a.label('leave_vbl')
    a.ldh_a_n(0x44)
    a.cp_n(144)
    a.jr('leave_vbl', 'nc')
    a.ret()

    a.label('serial_isr')
    a.push_af()
    a.push_bc()
    a.push_hl()
    a.ldh_a_n(0x01)                       # received byte
    a.ld_r_r('b', 'a')
    a.ld_a_nn(0xC111)
    a.cp_n(128)
    a.jr('nolog', 'nc')
    a.add_a_a()
    a.ld_r_r('l', 'a')
    a.ld_r_n('h', 0xC2)                   # hl = C200 + 2n
    a.ld_a_nn(0xC115)
    a.ld_hlp_a()                          # sent
    a.ld_r_r('a', 'b')
    a.ld_hl_a()                           # received
    a.label('nolog')
    a.ld_a_nn(0xC111)
    a.inc_r('a')
    a.jr('nosat', 'z')
    a.ld_nn_a(0xC111)
    a.label('nosat')
    a.ld_a_nn(0xC110)
    a.inc_r('a')
    a.ld_nn_a(0xC110)
    a.ld_hl_nn(0xC102)
    a.add_a_hl()
    a.ld_nn_a(0xC115)                     # next byte to send
    a.ldh_n_a(0x01)
    a.ld_a_nn(0xC100)
    a.cp_n(1)
    a.jr('master_done', 'z')
    a.ld_r_n('a', 0x80)                   # slave: re-arm inside the handler (like Pokemon)
    a.ldh_n_a(0x02)
    a.jr('isr_end')
    a.label('master_done')
    a.xor_a()
    a.ld_nn_a(0xC117)
    a.label('isr_end')
    a.pop_hl()
    a.pop_bc()
    a.pop_af()
    a.reti()
    return a


def build_rom(path, cgb, title, mfr=b'\0\0\0\0', default_role=0):
    a = build(default_role)
    prog = a.resolve()
    rom = bytearray(0x8000)
    rom[0x100:0x104] = bytes((0x00, 0xC3, 0x50, 0x01))
    isr = a.labels['serial_isr']
    rom[0x58:0x5B] = bytes((0xC3, isr & 0xFF, isr >> 8))
    rom[0x104:0x134] = LOGO
    t = title.encode('ascii')[:11]
    rom[0x134:0x134 + len(t)] = t
    rom[0x13F:0x143] = mfr
    rom[0x143] = 0xC0 if cgb else 0x00
    rom[0x147] = 0x00                     # ROM only
    rom[0x148] = 0x00
    rom[0x149] = 0x00
    rom[0x14A] = 0x01
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
    build_rom(os.path.join(out, 'gblink_dmg.gb'), cgb=False, title='GBLINKTEST')
    build_rom(os.path.join(out, 'gblink_cgb.gbc'), cgb=True, title='GBLINKCGB', mfr=b'GBLK')
    build_rom(os.path.join(out, 'gblink_dmg_b.gb'), cgb=False, title='GBLINKTESTB')
    # starts as role 3 on its own (slave until A is pressed, then master): needs no outside poking, used by the netplay hash check
    build_rom(os.path.join(out, 'gblink_auto.gb'), cgb=False, title='GBLINKAUTO', default_role=3)


if __name__ == '__main__':
    main()
