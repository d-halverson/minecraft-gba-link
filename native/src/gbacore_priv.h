/* Private test hooks (not part of the exported C ABI; hidden in the shared library). */
#ifndef GBACORE_PRIV_H
#define GBACORE_PRIV_H

#include "gbacore.h"

uint32_t gba_priv_read32(gba_core* c, uint32_t addr);
uint16_t gba_priv_read16(gba_core* c, uint32_t addr);
void     gba_priv_write32(gba_core* c, uint32_t addr, uint32_t value);
/* 1 if a master transfer is waiting for replies / a child transfer is in flight. */
int      gba_priv_link_waiting(gba_core* c);
/* UNIX time the cartridge clock reports right now (the RTC source mGBA samples). */
int64_t  gba_priv_rtc_unix(gba_core* c);

int gba_priv_group_round(gba_group* g);

#endif
