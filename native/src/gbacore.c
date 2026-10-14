/* gbacore.c - GBA emulator shim on top of mGBA 0.10.5 with a serial-level link cable.
 *
 * See README.md for the link protocol. All functions of one core must be called from a
 * single thread. */
#include "gbacore.h"
#include "gbacore_priv.h"

#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/timing.h>
#include <mgba/gba/core.h>
#include <mgba/gba/interface.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/cart/gpio.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba/internal/gba/savedata.h>
#include <mgba/internal/gba/sio.h>
#include <mgba/internal/gba/video.h>
#include <mgba-util/vfs.h>

#include <fcntl.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define GBA_API_VERSION 2u

/* ------------------------------------------------------------------------------------ */
/* link layer                                                                           */
/* ------------------------------------------------------------------------------------ */

enum {
	MSG_START = 1, /* master -> all:   [1][src][seq][mode][data32 le][?] */
	MSG_REPLY = 2, /* child  -> all:   [2][src][seq][mode][data32 le][valid] */
	MSG_DONE  = 3, /* master -> all:   [3][src][seq][mode][validMask][0][0][0][data32 le x4] */
	MSG_MODE  = 4, /* any    -> all:   [4][src][0][mode]   (mode 0..2 or 0xFF = other) */
};

#define PEER_UNKNOWN 0xFE
#define PEER_OTHER   0xFF
#define QUEUE_LEN 512
#define PENDING_ARM_TIMEOUT_FRAMES 180
/* A child latches its outgoing data this many emulated cycles after the master's START was
 * delivered, so interrupt handlers triggered by the previous transfer's end have run. */
#define SETTLE_CYCLES 2048

enum MasterState { M_IDLE, M_WAIT, M_XFER };
enum ChildState { C_IDLE, C_PENDING, C_BUSY };

struct gba_link;

struct LinkDrv {
	struct GBASIODriver d;
	struct gba_link* L;
};

struct gba_link {
	struct gba_core* c;
	bool dead;

	int idx;
	int count;
	bool plugged;

	struct LinkDrv dNormal;
	struct LinkDrv dMulti;
	struct mTimingEvent evFinish;
	struct mTimingEvent evChild;

	/* master side */
	enum MasterState mstate;
	uint8_t seq;
	int xmode;           /* enum GBASIOMode of the active transfer */
	uint8_t expect;      /* bitmask of children we wait for */
	uint8_t got;         /* bitmask of children that replied (valid or not) */
	uint8_t valid;       /* bitmask of players whose data[] entry is real (bit 0 = master) */
	uint32_t data[4];
	uint16_t xctl;       /* SIOCNT at transfer start (baud / clock / length) */

	/* child side */
	enum ChildState cstate;
	uint8_t cseq;
	int cmode;
	int pendAge;         /* frames spent pending */
	bool ranSinceDone;   /* the core has executed code since the last transfer completed */
	bool pendSettled;    /* the child has emulated SETTLE_CYCLES since the START arrived */

	uint8_t peerMode[4];
	int announced;       /* last mode we announced (-2 = nothing) */

	uint8_t q[QUEUE_LEN][GBA_LINK_MSG_MAX];
	uint8_t qlen[QUEUE_LEN];
	unsigned qhead, qtail;
};

#define AUDIO_RING 16384 /* stereo frames */

struct gba_core {
	struct mCore* core;     /* must stay first (see gbacore_priv.h users) */
	struct GBA* gba;
	color_t* video;
	bool romLoaded;
	bool frameActive;
	uint32_t frameStart;
	int32_t cycleStart;
	struct VFile* saveVf;
	uint64_t lastSaveHash;
	int16_t* ring;
	size_t ringHead, ringCount;
	bool rtcVirtual;        /* virtual RTC active (holds one reference on the process-wide UTC time zone) */
	struct gba_link link;
};

/* ---- helpers ---- */

static uint16_t* io(struct gba_link* L) { return L->c->gba->memory.io; }
static struct GBASIO* sio(struct gba_link* L) { return &L->c->gba->sio; }

static void put32(uint8_t* p, uint32_t v) {
	p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}
static uint32_t get32(const uint8_t* p) {
	return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t) p[3] << 24);
}

static void emit(struct gba_link* L, const uint8_t* m, size_t n) {
	if (!L->plugged || n == 0 || n > GBA_LINK_MSG_MAX) {
		return;
	}
	if (L->qtail - L->qhead >= QUEUE_LEN) {
		/* queue overflow: drop the oldest message (consumer is not draining) */
		++L->qhead;
	}
	unsigned i = L->qtail++ % QUEUE_LEN;
	memcpy(L->q[i], m, n);
	L->qlen[i] = (uint8_t) n;
}

static bool isLinkMode(int mode) {
	return mode == SIO_NORMAL_8 || mode == SIO_NORMAL_32 || mode == SIO_MULTI;
}

static uint32_t noData(int mode) {
	return mode == SIO_NORMAL_8 ? 0xFFu : 0xFFFFFFFFu;
}

/* Recompute the SIOCNT / RCNT bits games use to detect a cable. */
static void sioRefresh(struct gba_link* L) {
	struct GBASIO* s = sio(L);
	if (s->mode == SIO_MULTI) {
		bool slave = L->idx > 0 || !L->plugged;
		bool ready = L->plugged;
		if (ready) {
			int i;
			for (i = 0; i < L->count; ++i) {
				if (i != L->idx && L->peerMode[i] == PEER_OTHER) {
					ready = false;
				}
			}
		}
		s->siocnt = GBASIOMultiplayerSetSlave(s->siocnt, slave);
		s->siocnt = GBASIOMultiplayerSetReady(s->siocnt, ready);
		if (slave) {
			s->rcnt |= 4;
		} else {
			s->rcnt &= ~4;
		}
	} else if (s->mode == SIO_NORMAL_8 || s->mode == SIO_NORMAL_32) {
		s->siocnt = L->plugged ? GBASIONormalClearSi(s->siocnt) : GBASIONormalSetSi(s->siocnt, 1);
	}
}

static void announce(struct gba_link* L) {
	int mode = sio(L)->mode;
	int m = isLinkMode(mode) ? mode : PEER_OTHER;
	if (m == L->announced) {
		return;
	}
	L->announced = m;
	if (L->plugged) {
		uint8_t msg[4] = { MSG_MODE, (uint8_t) L->idx, 0, (uint8_t) m };
		emit(L, msg, sizeof(msg));
	}
}

static void raiseIrq(struct gba_link* L, uint32_t late) {
	GBARaiseIRQ(L->c->gba, GBA_IRQ_SIO, late);
}

static void writeNormalRecv(struct gba_link* L, int mode, uint32_t v) {
	if (mode == SIO_NORMAL_8) {
		io(L)[REG_SIODATA8 >> 1] = v & 0xFF;
	} else {
		io(L)[REG_SIODATA32_LO >> 1] = v & 0xFFFF;
		io(L)[REG_SIODATA32_HI >> 1] = v >> 16;
	}
}

static uint32_t readSendData(struct gba_link* L, int mode) {
	uint16_t* r = io(L);
	switch (mode) {
	case SIO_NORMAL_8:
		return r[REG_SIODATA8 >> 1] & 0xFF;
	case SIO_NORMAL_32:
		return r[REG_SIODATA32_LO >> 1] | ((uint32_t) r[REG_SIODATA32_HI >> 1] << 16);
	default:
		return r[REG_SIOMLT_SEND >> 1];
	}
}

/* ---- master ---- */

static int32_t xferCycles(struct gba_link* L) {
	if (L->xmode == SIO_MULTI) {
		int players = L->plugged ? L->count : 1;
		return GBASIOCyclesPerTransfer[L->xctl & 3][players - 1];
	}
	int32_t cycles = (L->xctl & 2) ? 8 * 8 : 64 * 8;
	if (L->xmode == SIO_NORMAL_32) {
		cycles *= 4;
	}
	return cycles;
}

static void startXfer(struct gba_link* L) {
	L->mstate = M_XFER;
	mTimingDeschedule(&L->c->gba->timing, &L->evFinish);
	mTimingSchedule(&L->c->gba->timing, &L->evFinish, xferCycles(L));
}

static void masterFinish(struct mTiming* timing, void* user, uint32_t cyclesLate) {
	UNUSED(timing);
	struct gba_link* L = user;
	if (L->dead || L->mstate != M_XFER) {
		return;
	}
	struct GBASIO* s = sio(L);
	uint16_t* r = io(L);
	L->mstate = M_IDLE;
	int mode = L->xmode;
	if (mode != s->mode) {
		return;
	}
	if (mode == SIO_MULTI) {
		int i;
		for (i = 0; i < 4; ++i) {
			r[(REG_SIOMULTI0 >> 1) + i] = (L->valid & (1 << i)) ? (uint16_t) L->data[i] : 0xFFFF;
		}
		s->rcnt |= 1;
		s->siocnt = GBASIOMultiplayerClearBusy(s->siocnt);
		s->siocnt = GBASIOMultiplayerSetId(s->siocnt, 0);
		if (GBASIOMultiplayerIsIrq(s->siocnt)) {
			raiseIrq(L, cyclesLate);
		}
	} else {
		/* daisy chain: the master receives what the LAST player shifted out */
		int last = L->plugged ? L->count - 1 : 0;
		uint32_t v = (last > 0 && (L->valid & (1 << last))) ? L->data[last] : noData(mode);
		writeNormalRecv(L, mode, v);
		s->siocnt = GBASIONormalClearStart(s->siocnt);
		if (GBASIONormalIsIrq(s->siocnt)) {
			raiseIrq(L, cyclesLate);
		}
	}
}

/* Called from the SIOCNT write handler when the game starts a transfer using its own clock. */
static void masterBegin(struct gba_link* L, int mode, uint16_t ctl) {
	struct GBASIO* s = sio(L);
	uint16_t* r = io(L);
	++L->seq;
	L->xmode = mode;
	L->xctl = ctl;
	L->data[0] = readSendData(L, mode);
	L->data[1] = L->data[2] = L->data[3] = 0;
	L->valid = 1;
	L->got = 0;
	L->expect = (L->plugged && L->idx == 0) ? (uint8_t) (((1 << L->count) - 1) & ~1) : 0;
	if (mode == SIO_MULTI) {
		r[REG_SIOMULTI0 >> 1] = r[REG_SIOMULTI1 >> 1] = r[REG_SIOMULTI2 >> 1] = r[REG_SIOMULTI3 >> 1] = 0xFFFF;
		s->rcnt &= ~1;
	}
	if (L->expect) {
		uint8_t m[9] = { MSG_START, (uint8_t) L->idx, L->seq, (uint8_t) mode };
		put32(m + 4, L->data[0]);
		m[8] = (uint8_t) L->count;
		emit(L, m, sizeof(m));
		L->mstate = M_WAIT;
		/* stop the CPU loop right after this instruction so gba_run_frame can return LINK_WAIT */
		struct ARMCore* cpu = L->c->core->cpu;
		L->c->gba->earlyExit = true;
		cpu->nextEvent = cpu->cycles;
	} else {
		startXfer(L);
	}
}

static void masterComplete(struct gba_link* L) {
	/* every expected reply is in (or declared missing): tell the children and run the clock */
	uint8_t m[24];
	memset(m, 0, sizeof(m));
	m[0] = MSG_DONE;
	m[1] = (uint8_t) L->idx;
	m[2] = L->seq;
	m[3] = (uint8_t) L->xmode;
	m[4] = L->valid;
	int i;
	for (i = 0; i < 4; ++i) {
		put32(m + 8 + 4 * i, L->data[i]);
	}
	if (L->plugged && L->idx == 0) {
		emit(L, m, sizeof(m));
	}
	startXfer(L);
}

static void masterReply(struct gba_link* L, int from, const uint8_t* d, size_t n) {
	if (L->mstate != M_WAIT || n < 9 || d[2] != L->seq || from < 1 || from > 3 || !(L->expect & (1 << from))
	    || (L->got & (1 << from))) {
		return;
	}
	L->got |= 1 << from;
	if (d[8]) {
		L->data[from] = get32(d + 4);
		L->valid |= 1 << from;
	}
	if (L->got == L->expect) {
		masterComplete(L);
	}
}

/* ---- child ---- */

static void sendReply(struct gba_link* L, uint8_t seq, int mode, bool valid, uint32_t data) {
	uint8_t m[9] = { MSG_REPLY, (uint8_t) L->idx, seq, (uint8_t) mode };
	put32(m + 4, data);
	m[8] = valid ? 1 : 0;
	emit(L, m, sizeof(m));
}

static bool normalArmed(uint16_t ctl) {
	return (ctl & 0x0081) == 0x0080; /* start set, external clock */
}

static void childAccept(struct gba_link* L, uint8_t seq, int mode) {
	struct GBASIO* s = sio(L);
	uint16_t* r = io(L);
	sendReply(L, seq, mode, true, readSendData(L, mode));
	L->cstate = C_BUSY;
	L->cseq = seq;
	L->cmode = mode;
	if (mode == SIO_MULTI) {
		r[REG_SIOMULTI0 >> 1] = r[REG_SIOMULTI1 >> 1] = r[REG_SIOMULTI2 >> 1] = r[REG_SIOMULTI3 >> 1] = 0xFFFF;
		s->siocnt = GBASIOMultiplayerFillBusy(s->siocnt);
		s->rcnt &= ~1;
	}
}

/* Complete the child's transfer. recv==NULL: no partner info -> everything reads as "no data". */
static void childFinish(struct gba_link* L, const uint8_t* done) {
	struct GBASIO* s = sio(L);
	uint16_t* r = io(L);
	int mode = L->cmode;
	L->cstate = C_IDLE;
	L->ranSinceDone = false;
	if (mode != s->mode) {
		return;
	}
	uint8_t valid = done ? done[4] : 0;
	uint32_t data[4] = { 0, 0, 0, 0 };
	int i;
	if (done) {
		for (i = 0; i < 4; ++i) {
			data[i] = get32(done + 8 + 4 * i);
		}
	}
	if (mode == SIO_MULTI) {
		for (i = 0; i < 4; ++i) {
			r[(REG_SIOMULTI0 >> 1) + i] = (valid & (1 << i)) ? (uint16_t) data[i] : 0xFFFF;
		}
		s->rcnt |= 1;
		s->siocnt = GBASIOMultiplayerClearBusy(s->siocnt);
		s->siocnt = GBASIOMultiplayerSetId(s->siocnt, L->idx);
		if (GBASIOMultiplayerIsIrq(s->siocnt)) {
			raiseIrq(L, 0);
		}
	} else {
		int prev = L->idx - 1;
		uint32_t v = (prev >= 0 && (valid & (1 << prev))) ? data[prev] : noData(mode);
		writeNormalRecv(L, mode, v);
		s->siocnt = GBASIONormalClearStart(s->siocnt);
		if (GBASIONormalIsIrq(s->siocnt)) {
			raiseIrq(L, 0);
		}
	}
}

/* Try to take part in the transfer announced by the pending START. */
static void childTryPending(struct gba_link* L, uint16_t ctl) {
	if (L->cstate != C_PENDING || !L->pendSettled) {
		return;
	}
	int mode = sio(L)->mode;
	if (mode != L->cmode) {
		sendReply(L, L->cseq, L->cmode, false, 0);
		L->cstate = C_IDLE;
	} else if (mode == SIO_MULTI || normalArmed(ctl)) {
		/* Normal mode: like a real slave, hold the line until the game has armed the transfer */
		childAccept(L, L->cseq, mode);
	}
}

static void childSettle(struct mTiming* timing, void* user, uint32_t cyclesLate) {
	UNUSED(timing);
	UNUSED(cyclesLate);
	struct gba_link* L = user;
	if (L->dead || L->cstate != C_PENDING) {
		return;
	}
	L->pendSettled = true;
	childTryPending(L, sio(L)->siocnt);
}

static void childStart(struct gba_link* L, const uint8_t* d, size_t n) {
	if (!L->plugged || L->idx == 0 || n < 9) {
		return;
	}
	int mode = d[3];
	uint8_t seq = d[2];
	mTimingDeschedule(&L->c->gba->timing, &L->evChild);
	L->cstate = C_IDLE; /* any older transfer we never heard the end of is abandoned */
	if (!isLinkMode(mode)) {
		sendReply(L, seq, mode, false, 0);
		return;
	}
	L->cstate = C_PENDING;
	L->cseq = seq;
	L->cmode = mode;
	L->pendAge = 0;
	if (L->ranSinceDone) {
		/* Like hardware: latch the outgoing data at the moment the master clocks. The reply is
		 * queued right here, so it is pollable as soon as gba_link_deliver returns. */
		L->pendSettled = true;
		childTryPending(L, sio(L)->siocnt);
	} else {
		/* We completed a transfer and the game has not run since (its SIO interrupt handler
		 * has not executed yet, so SIODATA is stale): let it run SETTLE_CYCLES first. */
		L->pendSettled = false;
		mTimingSchedule(&L->c->gba->timing, &L->evChild, SETTLE_CYCLES);
	}
}

/* ---- abort / reset ---- */

/* Finish whatever is in flight as if the partner was gone. */
static void linkAbort(struct gba_link* L) {
	if (L->mstate == M_WAIT) {
		L->valid &= 1; /* nobody else's data is trustworthy */
		L->got = L->expect;
		L->expect = 0;
		startXfer(L);
	}
	if (L->cstate == C_PENDING) {
		mTimingDeschedule(&L->c->gba->timing, &L->evChild);
		sendReply(L, L->cseq, L->cmode, false, 0);
		L->cstate = C_IDLE;
	} else if (L->cstate == C_BUSY) {
		childFinish(L, NULL);
	}
}

static void linkReset(struct gba_link* L) {
	mTimingDeschedule(&L->c->gba->timing, &L->evFinish);
	mTimingDeschedule(&L->c->gba->timing, &L->evChild);
	L->mstate = M_IDLE;
	L->cstate = C_IDLE;
	L->announced = -2;
	/* Link bookkeeping that is not part of a save state gets fixed values here (this runs on load_rom, reset and
	 * load_state), so a core that loads a state behaves exactly like any other core that loads the same state.
	 * Input-sync netplay relies on that for bit-exact determinism between machines. */
	L->ranSinceDone = true;
	L->pendSettled = false;
	L->pendAge = 0;
	L->seq = 0;
	L->cseq = 0;
	L->qhead = L->qtail = 0;
	struct GBASIO* s = sio(L);
	s->siocnt &= ~0x0080;
	sioRefresh(L);
}

/* ---- SIO driver callbacks ---- */

static bool drvInit(struct GBASIODriver* d) { UNUSED(d); return true; }
static void drvDeinit(struct GBASIODriver* d) { UNUSED(d); }

static bool drvLoad(struct GBASIODriver* d) {
	struct gba_link* L = ((struct LinkDrv*) d)->L;
	if (L->dead) {
		return true;
	}
	sioRefresh(L);
	return true;
}

static bool drvUnload(struct GBASIODriver* d) {
	struct gba_link* L = ((struct LinkDrv*) d)->L;
	if (L->dead) {
		return true;
	}
	/* leaving the mode: whatever was in flight ends without a partner */
	mTimingDeschedule(&L->c->gba->timing, &L->evFinish);
	mTimingDeschedule(&L->c->gba->timing, &L->evChild);
	if (L->cstate == C_PENDING) {
		sendReply(L, L->cseq, L->cmode, false, 0);
	}
	L->mstate = M_IDLE;
	L->cstate = C_IDLE;
	return true;
}

static uint16_t drvWriteNormal(struct GBASIODriver* d, uint32_t address, uint16_t value) {
	struct gba_link* L = ((struct LinkDrv*) d)->L;
	if (address != REG_SIOCNT) {
		return value;
	}
	struct GBASIO* s = sio(L);
	int mode = s->mode;
	value &= 0xFF8B; /* bits 2 (SI), 4-6 are not writable */
	value = L->plugged ? GBASIONormalClearSi(value) : GBASIONormalSetSi(value, 1);
	if (value & 0x0080) {
		if (value & 0x0001) {
			if (L->mstate == M_IDLE) {
				masterBegin(L, mode, value);
			}
			/* else: already running, writes are ignored */
		} else {
			childTryPending(L, value);
		}
	}
	return value;
}

static uint16_t drvWriteMulti(struct GBASIODriver* d, uint32_t address, uint16_t value) {
	struct gba_link* L = ((struct LinkDrv*) d)->L;
	struct GBASIO* s = sio(L);
	if (address == REG_SIOCNT) {
		uint16_t cur = s->siocnt;
		bool wantStart = value & 0x0080;
		value &= 0xFF03;
		value |= cur & 0x007C;
		/* bit 7 (busy/start) is owned by the driver, except the master may raise it when idle */
		value &= ~0x0080;
		bool canMaster = L->idx == 0 || !L->plugged;
		if (L->mstate != M_IDLE || L->cstate == C_BUSY) {
			value |= 0x0080;
		} else if (canMaster && wantStart) {
			value |= 0x0080;
			masterBegin(L, SIO_MULTI, value);
		}
		/* SD (ready) and SI (child) terminals reflect the cable */
		bool slave = L->idx > 0 || !L->plugged;
		bool ready = L->plugged;
		int i;
		for (i = 0; ready && i < L->count; ++i) {
			if (i != L->idx && L->peerMode[i] == PEER_OTHER) {
				ready = false;
			}
		}
		value = GBASIOMultiplayerSetSlave(value, slave);
		value = GBASIOMultiplayerSetReady(value, ready);
		return value;
	}
	if (address == REG_SIOMLT_SEND) {
		return value;
	}
	/* SIOMULTI0-3 are read-only for the game */
	return io(L)[address >> 1];
}

static void linkInit(struct gba_core* c) {
	struct gba_link* L = &c->link;
	memset(L, 0, sizeof(*L));
	L->c = c;
	L->idx = 0;
	L->count = 1;
	L->announced = -2;
	L->ranSinceDone = true;
	memset(L->peerMode, PEER_UNKNOWN, sizeof(L->peerMode));

	L->evFinish.name = "GBA Link Transfer";
	L->evFinish.callback = masterFinish;
	L->evFinish.context = L;
	L->evFinish.priority = 0x80;

	L->evChild.name = "GBA Link Child Settle";
	L->evChild.callback = childSettle;
	L->evChild.context = L;
	L->evChild.priority = 0x80;

	L->dNormal.L = L;
	L->dNormal.d.init = drvInit;
	L->dNormal.d.deinit = drvDeinit;
	L->dNormal.d.load = drvLoad;
	L->dNormal.d.unload = drvUnload;
	L->dNormal.d.writeRegister = drvWriteNormal;
	L->dNormal.d.p = &c->gba->sio;
	L->dMulti = L->dNormal;
	L->dMulti.d.writeRegister = drvWriteMulti;

	c->gba->sio.drivers.normal = &L->dNormal.d;
	c->gba->sio.drivers.multiplayer = &L->dMulti.d;
}

/* ------------------------------------------------------------------------------------ */
/* virtual RTC                                                                          */
/* ------------------------------------------------------------------------------------ */

/* mGBA turns the clock into the cartridge's BCD registers with localtime_r() and mktime(), i.e. through the process
 * time zone. The virtual clock must read the same on every machine, so while at least one core uses it the process
 * time zone is forced to UTC (reference counted, restored when the last such core leaves virtual mode). Java and
 * Minecraft keep their own zone data, so this does not affect them; only C library time conversions are affected. */
static atomic_flag g_tzLock = ATOMIC_FLAG_INIT;
static int g_tzUsers;
static char* g_tzSaved;

static void tzLock(void) { while (atomic_flag_test_and_set_explicit(&g_tzLock, memory_order_acquire)) { } }
static void tzUnlock(void) { atomic_flag_clear_explicit(&g_tzLock, memory_order_release); }

static void tzAcquire(void) {
	tzLock();
	if (g_tzUsers++ == 0) {
		const char* t = getenv("TZ");
		g_tzSaved = t ? strdup(t) : NULL;
#ifdef _WIN32
		_putenv_s("TZ", "UTC0");
		_tzset();
#else
		setenv("TZ", "UTC", 1);
		tzset();
#endif
	}
	tzUnlock();
}

static void tzRelease(void) {
	tzLock();
	if (g_tzUsers > 0 && --g_tzUsers == 0) {
#ifdef _WIN32
		_putenv_s("TZ", g_tzSaved ? g_tzSaved : "");
		_tzset();
#else
		if (g_tzSaved) {
			setenv("TZ", g_tzSaved, 1);
		} else {
			unsetenv("TZ");
		}
		tzset();
#endif
		free(g_tzSaved);
		g_tzSaved = NULL;
	}
	tzUnlock();
}

/* Milliseconds the generic RTC source (RTC_FAKE_EPOCH) reports right now; mirrors mGBA's _rtcGenericCallback. */
static int64_t rtcFakeMs(struct mCore* core) {
	return core->rtc.value + (int64_t) core->frameCounter(core) * ((int64_t) core->frameCycles(core) * 1000LL) / core->frequency(core);
}

static uint8_t unBcd(uint8_t b) { return (uint8_t) ((b >> 4) * 10 + (b & 0xF)); }

/* After leaving the virtual clock: recompute the save-time offset exactly like GBASavedataRTCRead does, but with the
 * local time zone again, so the in-game clock continues from the time it showed instead of jumping by the UTC offset. */
static void rtcRebaseOffset(gba_core* c) {
	struct GBACartridgeHardware* hw = &c->gba->memory.hw;
	if (!(hw->devices & HW_RTC) || hw->rtc.lastLatch == 0) {
		return;
	}
	struct tm date;
	memset(&date, 0, sizeof(date));
	date.tm_year = unBcd(hw->rtc.time[0]) + 100;
	date.tm_mon = unBcd(hw->rtc.time[1]) - 1;
	date.tm_mday = unBcd(hw->rtc.time[2]);
	date.tm_hour = unBcd(hw->rtc.time[4]);
	date.tm_min = unBcd(hw->rtc.time[5]);
	date.tm_sec = unBcd(hw->rtc.time[6]);
	date.tm_isdst = -1;
	time_t t = mktime(&date);
	hw->rtc.offset = hw->rtc.lastLatch - t;
}

/* ------------------------------------------------------------------------------------ */
/* core                                                                                 */
/* ------------------------------------------------------------------------------------ */

static void nullLog(struct mLogger* l, int category, enum mLogLevel level, const char* fmt, va_list args) {
	UNUSED(l); UNUSED(category); UNUSED(level); UNUSED(fmt); UNUSED(args);
}
static struct mLogger g_nullLogger = { nullLog, NULL };

GBA_API uint32_t gba_api_version(void) { return GBA_API_VERSION; }

GBA_API gba_core* gba_create(void) {
	mLogSetDefaultLogger(&g_nullLogger);
	struct gba_core* c = calloc(1, sizeof(*c));
	if (!c) {
		return NULL;
	}
	c->core = GBACoreCreate();
	if (!c->core || !c->core->init(c->core)) {
		free(c);
		return NULL;
	}
	struct mCore* core = c->core;
	mCoreInitConfig(core, NULL);
	c->gba = core->board;
	c->video = calloc((size_t) GBA_WIDTH * GBA_HEIGHT, sizeof(color_t));
	c->ring = calloc((size_t) AUDIO_RING * 2, sizeof(int16_t));
	if (!c->video || !c->ring) {
		core->deinit(core);
		free(c->video);
		free(c->ring);
		free(c);
		return NULL;
	}
	core->setVideoBuffer(core, c->video, GBA_WIDTH);
	core->setAudioBufferSize(core, 4096);
	blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), GBA_AUDIO_RATE);
	blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), GBA_AUDIO_RATE);
	linkInit(c);
	return c;
}

GBA_API void gba_destroy(gba_core* c) {
	if (!c) {
		return;
	}
	c->link.dead = true;
	if (c->rtcVirtual) {
		c->rtcVirtual = false;
		tzRelease();
	}
	mCoreConfigDeinit(&c->core->config);
	c->core->deinit(c->core); /* unloads ROM, closes the save VFile and frees the core */
	free(c->video);
	free(c->ring);
	free(c);
}

static void resetFrameState(gba_core* c) {
	c->frameActive = false;
	c->ringHead = c->ringCount = 0;
	blip_clear(c->core->getAudioChannel(c->core, 0));
	blip_clear(c->core->getAudioChannel(c->core, 1));
}

GBA_API int gba_load_rom(gba_core* c, const char* path) {
	if (!c || !path) {
		return -1;
	}
	struct mCore* core = c->core;
	if (c->romLoaded) {
		core->unloadROM(core); /* also closes the old save VFile */
		c->romLoaded = false;
		c->saveVf = NULL;
	}
	struct VFile* src = VFileOpen(path, O_RDONLY);
	if (!src) {
		return -1;
	}
	/* Read the whole ROM into memory and call GBALoadROM directly: going through core->loadROM
	 * would run mGBA's "multiboot" heuristic, which misdetects small cartridges as EWRAM images. */
	ssize_t size = src->size(src);
	if (size <= 0 || size > SIZE_CART0) {
		src->close(src);
		return -2;
	}
	uint8_t* buf = malloc((size_t) size);
	if (!buf) {
		src->close(src);
		return -2;
	}
	ssize_t got = src->read(src, buf, (size_t) size);
	src->close(src);
	if (got != size) {
		free(buf);
		return -2;
	}
	struct VFile* vf = VFileMemChunk(buf, (size_t) size);
	free(buf);
	if (!vf || !GBALoadROM(c->gba, vf)) { /* takes ownership of vf */
		return -2;
	}
	c->saveVf = VFileMemChunk(NULL, 0);
	if (!c->saveVf || !core->loadSave(core, c->saveVf)) {
		return -3;
	}
	core->reset(core);
	c->romLoaded = true;
	c->lastSaveHash = 0;
	resetFrameState(c);
	linkReset(&c->link);
	return 0;
}

GBA_API void gba_reset(gba_core* c) {
	if (!c || !c->romLoaded) {
		return;
	}
	c->core->reset(c->core);
	resetFrameState(c);
	linkReset(&c->link);
}

GBA_API int gba_rom_info(gba_core* c, char* title_out, char* code_out) {
	if (!c || !c->romLoaded) {
		return -1;
	}
	char title[32] = { 0 };
	char code[32] = { 0 };
	c->core->getGameTitle(c->core, title);
	c->core->getGameCode(c->core, code);
	memcpy(title_out, title, 12);
	title_out[12] = 0;
	if (strncmp(code, "AGB-", 4) == 0) {
		memcpy(code_out, code + 4, 4);
	} else {
		memset(code_out, '?', 4);
	}
	code_out[4] = 0;
	return 0;
}

GBA_API void gba_set_keys(gba_core* c, uint32_t keymask) {
	if (c) {
		c->core->setKeys(c->core, keymask & 0x3FF);
	}
}

static void drainAudio(gba_core* c) {
	struct mCore* core = c->core;
	struct blip_t* l = core->getAudioChannel(core, 0);
	struct blip_t* r = core->getAudioChannel(core, 1);
	int avail;
	while ((avail = blip_samples_avail(l)) > 0) {
		int16_t tmp[1024];
		int n = avail > 512 ? 512 : avail;
		blip_read_samples(l, tmp, n, 1);
		blip_read_samples(r, tmp + 1, n, 1);
		int i;
		for (i = 0; i < n; ++i) {
			if (c->ringCount == AUDIO_RING) { /* overflow: drop oldest */
				c->ringHead = (c->ringHead + 1) % AUDIO_RING;
				--c->ringCount;
			}
			size_t pos = (c->ringHead + c->ringCount) % AUDIO_RING;
			c->ring[pos * 2] = tmp[i * 2];
			c->ring[pos * 2 + 1] = tmp[i * 2 + 1];
			++c->ringCount;
		}
	}
}

static void finishFrame(gba_core* c) {
	color_t* p = c->video;
	size_t i;
	for (i = 0; i < (size_t) GBA_WIDTH * GBA_HEIGHT; ++i) {
		p[i] |= 0xFF000000u; /* mGBA writes XBGR8: bytes R,G,B,X in memory -> force A=255 */
	}
}

GBA_API int gba_run_frame(gba_core* c) {
	if (!c || !c->romLoaded) {
		return GBA_RUN_ERROR;
	}
	struct gba_link* L = &c->link;
	if (L->mstate == M_WAIT) {
		return GBA_RUN_LINK_WAIT;
	}
	L->ranSinceDone = true;
	struct mCore* core = c->core;
	struct GBA* gba = c->gba;
	if (!c->frameActive) {
		c->frameActive = true;
		c->frameStart = gba->video.frameCounter;
		c->cycleStart = mTimingCurrentTime(&gba->timing);
	}
	while (gba->video.frameCounter == c->frameStart
	       && mTimingCurrentTime(&gba->timing) - c->cycleStart < VIDEO_TOTAL_LENGTH + VIDEO_HORIZONTAL_LENGTH) {
		ARMRunLoop(core->cpu);
		if (L->mstate == M_WAIT) {
			drainAudio(c);
			announce(L);
			return GBA_RUN_LINK_WAIT;
		}
	}
	c->frameActive = false;
	finishFrame(c);
	drainAudio(c);
	/* per-frame link housekeeping */
	announce(L);
	if (L->cstate == C_PENDING) {
		childTryPending(L, sio(L)->siocnt);
		if (L->cstate == C_PENDING && ++L->pendAge > PENDING_ARM_TIMEOUT_FRAMES) {
			sendReply(L, L->cseq, L->cmode, false, 0);
			L->cstate = C_IDLE;
		}
	}
	return GBA_RUN_FRAME_DONE;
}

GBA_API const uint8_t* gba_framebuffer(gba_core* c) {
	return c ? (const uint8_t*) c->video : NULL;
}

GBA_API size_t gba_read_audio(gba_core* c, int16_t* out, size_t max_frames) {
	if (!c || !out) {
		return 0;
	}
	size_t n = max_frames < c->ringCount ? max_frames : c->ringCount;
	size_t i;
	for (i = 0; i < n; ++i) {
		size_t pos = (c->ringHead + i) % AUDIO_RING;
		out[i * 2] = c->ring[pos * 2];
		out[i * 2 + 1] = c->ring[pos * 2 + 1];
	}
	c->ringHead = (c->ringHead + n) % AUDIO_RING;
	c->ringCount -= n;
	return n;
}

/* ---- save data ---- */

static size_t saveBytes(gba_core* c) {
	struct GBASavedata* sd = &c->gba->memory.savedata;
	switch (sd->type) {
	case SAVEDATA_AUTODETECT:
		return c->saveVf ? (size_t) c->saveVf->size(c->saveVf) : 0;
	case SAVEDATA_FORCE_NONE:
		return 0;
	default: {
		size_t n = GBASavedataSize(sd);
		if (c->gba->memory.hw.devices & HW_RTC) {
			n += sizeof(struct GBASavedataRTCBuffer);
		}
		return n;
	}
	}
}

static uint64_t hashBytes(const uint8_t* p, size_t n) {
	uint64_t h = 1469598103934665603ull;
	size_t i;
	for (i = 0; i < n; ++i) {
		h = (h ^ p[i]) * 1099511628211ull;
	}
	return h;
}

GBA_API size_t gba_save_size(gba_core* c) {
	return (c && c->romLoaded) ? saveBytes(c) : 0;
}

static size_t readSave(gba_core* c, uint8_t* out, size_t n) {
	struct VFile* vf = c->saveVf;
	size_t have = (size_t) vf->size(vf);
	if (n > have) {
		memset(out + have, 0xFF, n - have);
		n = have;
	}
	vf->seek(vf, 0, SEEK_SET);
	return (size_t) vf->read(vf, out, n);
}

GBA_API size_t gba_get_save(gba_core* c, uint8_t* out, size_t cap) {
	if (!c || !c->romLoaded || !out) {
		return 0;
	}
	size_t n = saveBytes(c);
	if (!n || n > cap) {
		return 0;
	}
	GBASavedataRTCWrite(&c->gba->memory.savedata);
	n = saveBytes(c);
	if (n > cap) {
		return 0;
	}
	size_t got = readSave(c, out, n);
	c->lastSaveHash = hashBytes(out, got);
	return got;
}

GBA_API int gba_load_save(gba_core* c, const uint8_t* data, size_t len) {
	if (!c || !c->romLoaded || (!data && len)) {
		return -1;
	}
	struct VFile* vf = VFileMemChunk(data, len);
	if (!vf) {
		return -2;
	}
	/* GBALoadSave closes the previous (empty) backing file for us */
	if (!c->core->loadSave(c->core, vf)) {
		return -3;
	}
	c->saveVf = vf;
	uint8_t* tmp = malloc(len ? len : 1);
	if (tmp) {
		size_t got = readSave(c, tmp, len);
		c->lastSaveHash = hashBytes(tmp, got);
		free(tmp);
	}
	return 0;
}

GBA_API int gba_save_dirty(gba_core* c) {
	if (!c || !c->romLoaded) {
		return 0;
	}
	size_t n = saveBytes(c);
	if (!n) {
		return 0;
	}
	uint8_t* tmp = malloc(n);
	if (!tmp) {
		return 0;
	}
	size_t got = readSave(c, tmp, n);
	uint64_t h = hashBytes(tmp, got);
	free(tmp);
	return h != c->lastSaveHash;
}

/* ---- save states ---- */

GBA_API size_t gba_state_size(gba_core* c) {
	return c ? c->core->stateSize(c->core) : 0;
}

GBA_API size_t gba_save_state(gba_core* c, uint8_t* out, size_t cap) {
	if (!c || !c->romLoaded || !out) {
		return 0;
	}
	size_t n = c->core->stateSize(c->core);
	if (cap < n) {
		return 0;
	}
	memset(out, 0, n); /* padding and unused fields must not carry stale bytes: states are hashed for desync checks */
	if (!c->core->saveState(c->core, out)) {
		return 0;
	}
	return n;
}

GBA_API int gba_load_state(gba_core* c, const uint8_t* data, size_t len) {
	if (!c || !c->romLoaded || !data || len != c->core->stateSize(c->core)) {
		return -1;
	}
	int64_t clockMs = c->rtcVirtual ? rtcFakeMs(c->core) : 0;
	if (!c->core->loadState(c->core, data)) {
		return -2;
	}
	/* in-flight link transfers are not part of a state: drop them */
	c->frameActive = false;
	linkReset(&c->link);
	if (c->rtcVirtual) {
		/* the state carries its own frame counter; keep the virtual clock where it was instead of jumping */
		c->core->rtc.value += clockMs - rtcFakeMs(c->core);
	}
	return 0;
}

/* ---- link API ---- */

GBA_API void gba_link_configure(gba_core* c, int player_index, int player_count) {
	if (!c) {
		return;
	}
	struct gba_link* L = &c->link;
	bool plugged = player_count >= 2 && player_count <= 4 && player_index >= 0 && player_index < player_count;
	int idx = plugged ? player_index : 0;
	int count = plugged ? player_count : 1;
	if (plugged == L->plugged && idx == L->idx && count == L->count) {
		return;
	}
	if (c->romLoaded) {
		linkAbort(L); /* uses the old configuration for timing */
	}
	L->plugged = plugged;
	L->idx = idx;
	L->count = count;
	if (!plugged) {
		L->qhead = L->qtail = 0;
		memset(L->peerMode, PEER_UNKNOWN, sizeof(L->peerMode));
	} else {
		int i;
		for (i = count; i < 4; ++i) {
			L->peerMode[i] = PEER_UNKNOWN;
		}
	}
	L->announced = -2;
	if (c->romLoaded) {
		sioRefresh(L);
		announce(L);
	}
}

GBA_API size_t gba_link_poll_outgoing(gba_core* c, uint8_t* out, size_t cap) {
	if (!c || !out) {
		return 0;
	}
	struct gba_link* L = &c->link;
	if (L->qhead == L->qtail) {
		return 0;
	}
	unsigned i = L->qhead % QUEUE_LEN;
	size_t n = L->qlen[i];
	if (cap < n) {
		return 0;
	}
	memcpy(out, L->q[i], n);
	++L->qhead;
	return n;
}

GBA_API void gba_link_deliver(gba_core* c, int from, const uint8_t* d, size_t len) {
	if (!c || !d || len < 4 || from < 0 || from > 3) {
		return;
	}
	struct gba_link* L = &c->link;
	if (!c->romLoaded) {
		/* no game running here: answer a master's START with "no data" so it never blocks on us */
		if (d[0] == MSG_START && L->plugged && L->idx > 0 && from == 0 && len >= 9) {
			sendReply(L, d[2], d[3], false, 0);
		}
		return;
	}
	if (from == L->idx && L->plugged) {
		return; /* never accept our own messages */
	}
	switch (d[0]) {
	case MSG_MODE:
		L->peerMode[from] = d[3];
		sioRefresh(L);
		break;
	case MSG_START:
		if (L->plugged && from == 0) {
			childStart(L, d, len);
		}
		break;
	case MSG_REPLY:
		if (L->plugged && L->idx == 0) {
			masterReply(L, from, d, len);
		}
		break;
	case MSG_DONE:
		if (L->plugged && from == 0 && len >= 24 && L->cstate == C_BUSY && d[2] == L->cseq) {
			childFinish(L, d);
		}
		break;
	default:
		break;
	}
}

/* ---- virtual RTC ---- */

GBA_API int gba_set_rtc(gba_core* c, int64_t epoch_seconds, int mode) {
	if (!c) {
		return -1;
	}
	struct mRTCGenericSource* rtc = &c->core->rtc;
	if (mode == GBA_RTC_VIRTUAL) {
		if (!c->rtcVirtual) {
			tzAcquire();
			c->rtcVirtual = true;
		}
		rtc->override = RTC_FAKE_EPOCH;
		rtc->value = 0;
		rtc->value = epoch_seconds * 1000LL - rtcFakeMs(c->core);
		return 0;
	}
	if (mode == GBA_RTC_WALL) {
		if (c->rtcVirtual) {
			c->rtcVirtual = false;
			rtc->override = RTC_NO_OVERRIDE;
			rtc->value = 0;
			tzRelease();
			rtcRebaseOffset(c);
		}
		return 0;
	}
	return -2;
}

/* ---- private test hooks ---- */

uint32_t gba_priv_read32(gba_core* c, uint32_t addr) { return c->core->busRead32(c->core, addr); }
uint16_t gba_priv_read16(gba_core* c, uint32_t addr) { return (uint16_t) c->core->busRead16(c->core, addr); }
void gba_priv_write32(gba_core* c, uint32_t addr, uint32_t v) { c->core->busWrite32(c->core, addr, v); }
int64_t gba_priv_rtc_unix(gba_core* c) {
	struct mRTCSource* r = c->gba->rtcSource;
	return r ? (int64_t) r->unixTime(r) : 0;
}
int gba_priv_link_waiting(gba_core* c) {
	return c->link.mstate == M_WAIT || c->link.cstate == C_BUSY;
}
