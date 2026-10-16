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
#include <mgba/gb/core.h>
#include <mgba/gb/interface.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/io.h>
#include <mgba/internal/gb/mbc.h>
#include <mgba/internal/gb/video.h>
#include <mgba/gba/interface.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/cart/gpio.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba/internal/gba/savedata.h>
#include <mgba/internal/gba/sio.h>
#include <mgba/internal/gba/sio/lockstep.h>
#include <mgba/internal/gba/video.h>
#include <mgba-util/vfs.h>

#include <fcntl.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define GBA_API_VERSION 4u
#define GB_MAX_ROM_BYTES (8 * 1024 * 1024)

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
	struct GBA* gba;        /* NULL for a Game Boy core */
	struct GB* gb;          /* NULL for a GBA core (API 4) */
	int platform;           /* GBA_PLATFORM_* */
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
	/* API 3: member of a lockstep group (mGBA GBASIOLockstep) */
	struct gba_group* group;
	int gidx;
	color_t* shown;          /* last completed frame (group members) */
	uint8_t* stash;          /* lockstep trailer of a loaded state, applied when the core joins a group */
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
	if (!c->gba) {
		return; /* Game Boy: the cartridge clock works in UNIX time, no time zone involved */
	}
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

/* group support (defined below) */
#define GRP_TRAILER 128
static bool grpTrailerValid(const uint8_t* t);
static void grpSaveTrailer(gba_core* c, uint8_t* out);
static void grpBeforeLoad(gba_core* c);
static void grpAfterLoad(gba_core* c, const uint8_t* trailer);
static void grpDetach(gba_core* c);

GBA_API uint32_t gba_api_version(void) { return GBA_API_VERSION; }

/* Game Boy palette for monochrome (DMG) games, 0xRRGGBB, lightest first. */
static const uint32_t g_dmgPalette[4] = { 0xE0F8D0, 0x88C070, 0x346856, 0x081820 };

static int frameW(const gba_core* c) { return c->platform == GBA_PLATFORM_GB ? GB_WIDTH : GBA_WIDTH; }
static int frameH(const gba_core* c) { return c->platform == GBA_PLATFORM_GB ? GB_HEIGHT : GBA_HEIGHT; }

static void coreTeardown(gba_core* c) {
	if (!c->core) {
		return;
	}
	c->link.dead = true;
	mCoreConfigDeinit(&c->core->config);
	c->core->deinit(c->core); /* unloads ROM, closes the save VFile and frees the core */
	c->core = NULL;
	c->gba = NULL;
	c->gb = NULL;
	c->saveVf = NULL;
	c->romLoaded = false;
}

/* (Re)create the mGBA core for a platform. Returns false on failure (the gba_core then has no core). */
static bool coreInstall(gba_core* c, int platform) {
	coreTeardown(c);
	struct mCore* core = platform == GBA_PLATFORM_GB ? GBCoreCreate() : GBACoreCreate();
	if (!core || !core->init(core)) {
		return false;
	}
	mCoreInitConfig(core, NULL);
	c->core = core;
	c->platform = platform;
	c->frameActive = false;
	core->setVideoBuffer(core, c->video, platform == GBA_PLATFORM_GB ? GB_WIDTH : GBA_WIDTH);
	core->setAudioBufferSize(core, 4096);
	blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), GBA_AUDIO_RATE);
	blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), GBA_AUDIO_RATE);
	if (platform == GBA_PLATFORM_GB) {
		struct GB* gb = core->board;
		c->gb = gb;
		c->gba = NULL;
		core->opts.skipBios = true;
		core->opts.useBios = false; /* no BIOS file is ever read */
		/* Super Game Boy borders off; SGB-enhanced games run as plain DMG (no border, no SGB colours). */
		gb->video.sgbBorders = false;
		mCoreConfigSetValue(&core->config, "sgb.model", "DMG");
		/* classic green LCD palette for monochrome games (BG, OBJ0, OBJ1 use the same four shades) */
		int i;
		for (i = 0; i < 12; ++i) {
			GBVideoSetPalette(&gb->video, (unsigned) i, g_dmgPalette[i & 3]);
		}
		c->link.dead = true; /* no serial link for Game Boy cores yet */
	} else {
		c->gba = core->board;
		c->gb = NULL;
		linkInit(c);
	}
	return true;
}

GBA_API gba_core* gba_create(void) {
	mLogSetDefaultLogger(&g_nullLogger);
	struct gba_core* c = calloc(1, sizeof(*c));
	if (!c) {
		return NULL;
	}
	c->video = calloc((size_t) GBA_WIDTH * GBA_HEIGHT, sizeof(color_t));
	c->ring = calloc((size_t) AUDIO_RING * 2, sizeof(int16_t));
	if (!c->video || !c->ring || !coreInstall(c, GBA_PLATFORM_GBA)) {
		coreTeardown(c);
		free(c->video);
		free(c->ring);
		free(c);
		return NULL;
	}
	return c;
}

GBA_API void gba_destroy(gba_core* c) {
	if (!c) {
		return;
	}
	if (c->group) {
		grpDetach(c);
	}
	c->link.dead = true;
	if (c->rtcVirtual) {
		c->rtcVirtual = false;
		tzRelease();
	}
	coreTeardown(c);
	free(c->video);
	free(c->shown);
	free(c->stash);
	free(c->ring);
	free(c);
}

static void resetFrameState(gba_core* c) {
	c->frameActive = false;
	c->ringHead = c->ringCount = 0;
	blip_clear(c->core->getAudioChannel(c->core, 0));
	blip_clear(c->core->getAudioChannel(c->core, 1));
}

/* After a Game Boy core was reset: monochrome games need the palette registers re-evaluated with our shades. */
static void gbApplyPalette(gba_core* c) {
	struct GB* gb = c->gb;
	if (gb && gb->model < GB_MODEL_CGB) {
		GBVideoWritePalette(&gb->video, GB_REG_BGP, gb->memory.io[GB_REG_BGP]);
		GBVideoWritePalette(&gb->video, GB_REG_OBP0, gb->memory.io[GB_REG_OBP0]);
		GBVideoWritePalette(&gb->video, GB_REG_OBP1, gb->memory.io[GB_REG_OBP1]);
	}
}

GBA_API int gba_platform(gba_core* c) { return c ? c->platform : GBA_PLATFORM_GBA; }
GBA_API int gba_frame_width(gba_core* c) { return c ? frameW(c) : GBA_WIDTH; }
GBA_API int gba_frame_height(gba_core* c) { return c ? frameH(c) : GBA_HEIGHT; }

GBA_API int gba_load_rom(gba_core* c, const char* path) {
	if (!c || !path) {
		return -1;
	}
	if (c->group) {
		grpDetach(c); /* loading a ROM dissolves the lockstep group */
	}
	struct VFile* src = VFileOpen(path, O_RDONLY);
	if (!src) {
		return -1;
	}
	/* Read the whole ROM into memory and load it directly: going through core->loadROM would run mGBA's
	 * "multiboot" heuristic for GBA, which misdetects small cartridges as EWRAM images. */
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
	if (!vf) {
		return -2;
	}
	/* The system comes from the ROM contents (mGBA's header checks: Game Boy cartridge logo at 0x104), not from the
	 * file name. Everything that is not a Game Boy ROM is loaded as GBA, as before. */
	int platform = mCoreIsCompatible(vf) == mPLATFORM_GB ? GBA_PLATFORM_GB : GBA_PLATFORM_GBA;
	if (platform == GBA_PLATFORM_GB && size > GB_MAX_ROM_BYTES) {
		vf->close(vf);
		return -2;
	}
	bool wasLoaded = c->romLoaded;
	if (platform == GBA_PLATFORM_GB || c->platform != platform) {
		/* a fresh Game Boy core per ROM: mGBA keeps the detected hardware model across loads */
		if (c->rtcVirtual) {
			c->rtcVirtual = false;
			tzRelease();
		}
		if (!coreInstall(c, platform)) {
			vf->close(vf);
			return -3;
		}
	} else if (wasLoaded) {
		c->core->unloadROM(c->core); /* also closes the old save VFile */
		c->romLoaded = false;
		c->saveVf = NULL;
	}
	struct mCore* core = c->core;
	bool ok = platform == GBA_PLATFORM_GB ? GBLoadROM(c->gb, vf) : GBALoadROM(c->gba, vf); /* takes ownership of vf */
	if (!ok) {
		return -2;
	}
	c->saveVf = VFileMemChunk(NULL, 0);
	if (!c->saveVf || !core->loadSave(core, c->saveVf)) {
		return -3;
	}
	core->reset(core);
	gbApplyPalette(c);
	c->romLoaded = true;
	c->lastSaveHash = 0;
	resetFrameState(c);
	if (c->platform == GBA_PLATFORM_GBA) {
		linkReset(&c->link);
	}
	return 0;
}

GBA_API void gba_reset(gba_core* c) {
	if (!c || !c->romLoaded) {
		return;
	}
	c->core->reset(c->core);
	gbApplyPalette(c);
	resetFrameState(c);
	if (c->platform == GBA_PLATFORM_GBA) {
		linkReset(&c->link);
	}
}

/* Game Boy header text: printable ASCII only, stops at the first other byte. */
static void gbText(const uint8_t* src, size_t n, char* out) {
	size_t i;
	for (i = 0; i < n && src[i] >= 0x20 && src[i] < 0x7F; ++i) {
		out[i] = (char) src[i];
	}
	out[i] = 0;
}

static bool gbCodeChar(uint8_t b) { return (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9'); }

GBA_API int gba_rom_info(gba_core* c, char* title_out, char* code_out) {
	if (!c || !c->romLoaded) {
		return -1;
	}
	if (c->platform == GBA_PLATFORM_GB) {
		/* Title: up to 11 printable characters at 0x134 (the CGB title area; older cartridges continue to 0x143, which
		 * is cut off here). Code (always 4 characters): the manufacturer code at 0x13F..0x142 when it is four
		 * upper-case letters/digits (later CGB cartridges), else "G" followed by 3 hex digits: the header checksum
		 * (0x14D) and the low nibble of the global checksum (0x14F). Same rule in the mod's RomLibrary. */
		const uint8_t* rom = c->gb->memory.rom;
		if (!rom || c->gb->memory.romSize < 0x150) {
			return -1;
		}
		char t[16];
		gbText(rom + 0x134, 11, t);
		memset(title_out, 0, 13);
		memcpy(title_out, t, strlen(t));
		const uint8_t* m = rom + 0x13F;
		if (gbCodeChar(m[0]) && gbCodeChar(m[1]) && gbCodeChar(m[2]) && gbCodeChar(m[3])) {
			memcpy(code_out, m, 4);
		} else {
			static const char hex[] = "0123456789ABCDEF";
			code_out[0] = 'G';
			code_out[1] = hex[rom[0x14D] >> 4];
			code_out[2] = hex[rom[0x14D] & 15];
			code_out[3] = hex[rom[0x14F] & 15];
		}
		code_out[4] = 0;
		return 0;
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
		/* Game Boy: A B Select Start Right Left Up Down are bits 0-7 in the same order as the GBA; L/R (8, 9) are ignored */
		c->core->setKeys(c->core, keymask & (c->platform == GBA_PLATFORM_GB ? 0xFF : 0x3FF));
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
	for (i = 0; i < (size_t) frameW(c) * frameH(c); ++i) {
		p[i] |= 0xFF000000u; /* mGBA writes XBGR8: bytes R,G,B,X in memory -> force A=255 */
	}
}

GBA_API int gba_run_frame(gba_core* c) {
	if (!c || !c->romLoaded) {
		return GBA_RUN_ERROR;
	}
	if (c->group) {
		return GBA_RUN_ERROR; /* grouped cores run through gba_group_run_frame */
	}
	if (c->platform == GBA_PLATFORM_GB) {
		/* Like mCore::runFrame, but bounded: a broken or truncated ROM can sit in STOP / HALT with the LCD off and never finish
		 * a frame, which must not hang the emulator thread. After four frames' worth of cycles the frame is reported anyway. */
		struct GB* gb = c->gb;
		uint32_t fc = gb->video.frameCounter;
		int32_t t0 = mTimingCurrentTime(&gb->timing);
		while (gb->video.frameCounter == fc && mTimingCurrentTime(&gb->timing) - t0 < 4 * GB_VIDEO_TOTAL_LENGTH) {
			c->core->runLoop(c->core);
		}
		finishFrame(c);
		drainAudio(c);
		return GBA_RUN_FRAME_DONE;
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
	return c ? (const uint8_t*) (c->group ? c->shown : c->video) : NULL;
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

/* Game Boy: bytes after the cartridge RAM in the .sav file (clock footer of MBC3+RTC, HuC3, TAMA5 carts). */
static size_t gbFooterBytes(struct GB* gb) {
	switch (gb->memory.mbcType) {
	case GB_MBC3_RTC:
		return sizeof(struct GBMBCRTCSaveBuffer);
	case GB_HuC3:
		return sizeof(struct GBMBCHuC3SaveBuffer);
	case GB_TAMA5:
		return sizeof(struct GBMBCTAMA5SaveBuffer);
	default:
		return 0;
	}
}

static void gbWriteFooter(struct GB* gb) {
	if (gb->memory.mbcType == GB_MBC3_RTC) {
		GBMBCRTCWrite(gb);
	} else if (gb->memory.mbcType == GB_HuC3) {
		GBMBCHuC3Write(gb);
	} else if (gb->memory.mbcType == GB_TAMA5) {
		GBMBCTAMA5Write(gb);
	}
}

static size_t saveBytes(gba_core* c) {
	if (c->platform == GBA_PLATFORM_GB) {
		return c->gb->sramSize ? c->gb->sramSize + gbFooterBytes(c->gb) : 0;
	}
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
	if (c->platform == GBA_PLATFORM_GB) {
		gbWriteFooter(c->gb);
	} else {
		GBASavedataRTCWrite(&c->gba->memory.savedata);
	}
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

static size_t grpTrailer(gba_core* c);
GBA_API size_t gba_state_size(gba_core* c) {
	return c ? c->core->stateSize(c->core) + grpTrailer(c) : 0;
}

GBA_API size_t gba_save_state(gba_core* c, uint8_t* out, size_t cap) {
	if (!c || !c->romLoaded || !out) {
		return 0;
	}
	size_t base = c->core->stateSize(c->core);
	size_t n = base + grpTrailer(c);
	if (cap < n) {
		return 0;
	}
	memset(out, 0, n); /* padding and unused fields must not carry stale bytes: states are hashed for desync checks */
	if (!c->core->saveState(c->core, out)) {
		return 0;
	}
	if (c->group) {
		grpSaveTrailer(c, out + base);
	}
	return n;
}

GBA_API int gba_load_state(gba_core* c, const uint8_t* data, size_t len) {
	size_t base = c ? c->core->stateSize(c->core) : 0;
	if (!c || !c->romLoaded || !data || (len != base && !(len == base + GRP_TRAILER && c->platform == GBA_PLATFORM_GBA))) {
		return -1;
	}
	bool trailer = len == base + GRP_TRAILER && grpTrailerValid(data + base);
	int64_t clockMs = c->rtcVirtual ? rtcFakeMs(c->core) : 0;
	if (c->group) {
		grpBeforeLoad(c);
	}
	if (!c->core->loadState(c->core, data)) {
		return -2;
	}
	free(c->stash);
	c->stash = NULL;
	if (c->group) {
		grpAfterLoad(c, trailer ? data + base : NULL);
	} else if (trailer) {
		c->stash = malloc(GRP_TRAILER);
		if (c->stash) {
			memcpy(c->stash, data + base, GRP_TRAILER);
		}
	}
	/* in-flight link transfers are not part of a state: drop them */
	c->frameActive = false;
	if (c->platform == GBA_PLATFORM_GBA) {
		linkReset(&c->link);
	}
	if (c->rtcVirtual) {
		/* the state carries its own frame counter; keep the virtual clock where it was instead of jumping */
		c->core->rtc.value += clockMs - rtcFakeMs(c->core);
	}
	return 0;
}

/* ---- link API ---- */

GBA_API void gba_link_configure(gba_core* c, int player_index, int player_count) {
	if (!c || c->group || c->platform != GBA_PLATFORM_GBA) {
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
	if (!c || !out || c->platform != GBA_PLATFORM_GBA) {
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
	if (!c || !d || len < 4 || from < 0 || from > 3 || c->platform != GBA_PLATFORM_GBA) {
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


/* ------------------------------------------------------------------------------------ */
/* lockstep groups (API 3): mGBA's own GBASIOLockstep driving several cores in one thread */
/* ------------------------------------------------------------------------------------ */

/* mGBA's lockstep is written for one thread per core. Its mLockstep callbacks (wait / signal / addCycles / useCycles ...)
 * do not block in mGBA's own Qt frontend either: they only post "cycles you may run" to the followers and ask a core's
 * thread to stop or continue. Here a core "sleeps" simply by not being stepped. A fixed round-robin (index order, one
 * ARMRunLoop = up to one timing event per core per round) replaces the OS scheduler, so the result is deterministic.
 * The callbacks mirror src/platform/qt/MultiplayerController.cpp. */

struct gba_group {
	struct GBASIOLockstep ls;
	struct GBASIOLockstepNode nodes[MAX_GBAS];
	struct gba_core* cores[MAX_GBAS];
	struct {
		int awake;
		int32_t posted;
		unsigned waitMask;
	} p[MAX_GBAS];
	int expected, added;
	bool dissolved;
	long stalls;
};

static struct gba_group* grpOf(struct mLockstep* l) { return l->context; }

static bool cbSignal(struct mLockstep* l, unsigned mask) {
	struct gba_group* g = grpOf(l);
	bool woke = false;
	g->p[0].waitMask &= ~mask;
	if (!g->p[0].waitMask && g->p[0].awake < 1) {
		g->p[0].awake = 1;
		woke = true;
	}
	return woke;
}

static bool cbWait(struct mLockstep* l, unsigned mask) {
	struct gba_group* g = grpOf(l);
	bool slept = false;
	g->p[0].waitMask |= mask;
	if (g->p[0].awake > 0) {
		g->p[0].awake = 0;
		slept = true;
	}
	return slept;
}

static void cbAddCycles(struct mLockstep* l, int id, int32_t cycles) {
	struct gba_group* g = grpOf(l);
	if (cycles < 0) {
		abort();
	}
	if (id == 0) {
		int i;
		for (i = 1; i < g->added; ++i) {
			if (!g->cores[i] || (int) g->cores[i]->gba->sio.mode > (int) SIO_MULTI) {
				continue;
			}
			g->p[i].posted += cycles;
			if (g->p[i].awake < 1) {
				g->nodes[i].nextEvent += g->p[i].posted;
			}
			g->p[i].awake = 1;
		}
	} else {
		g->p[id].posted += cycles;
	}
}

static int32_t cbUseCycles(struct mLockstep* l, int id, int32_t cycles) {
	struct gba_group* g = grpOf(l);
	g->p[id].posted -= cycles;
	if (g->p[id].posted <= 0) {
		g->p[id].awake = 0;
	}
	return g->p[id].posted;
}

static int32_t cbUnusedCycles(struct mLockstep* l, int id) {
	return grpOf(l)->p[id].posted;
}

static void cbUnload(struct mLockstep* l, int id) {
	struct gba_group* g = grpOf(l);
	if (id) {
		g->p[id].posted = 0;
		g->p[0].waitMask &= ~(1u << id);
		if (!g->p[0].waitMask && g->p[0].awake < 1) {
			g->p[0].awake = 1;
		}
	} else {
		int i;
		for (i = 1; i < g->added; ++i) {
			g->p[i].posted += g->nodes[0].eventDiff;
			if (g->p[i].awake < 1) {
				g->nodes[i].nextEvent += g->p[i].posted;
				g->p[i].awake = 1;
			}
		}
	}
}

static bool grpLinkMode(int mode) {
	return mode == SIO_NORMAL_8 || mode == SIO_NORMAL_32 || mode == SIO_MULTI;
}

/* attachedMulti / attachedNormal are just the number of nodes in those modes */
static void grpRecount(struct gba_group* g) {
	int i, m = 0, n = 0;
	for (i = 0; i < g->added; ++i) {
		int mode = (int) g->nodes[i].mode;
		if (mode == SIO_MULTI) {
			++m;
		} else if (mode == SIO_NORMAL_8 || mode == SIO_NORMAL_32) {
			++n;
		}
	}
	g->ls.attachedMulti = m;
	g->ls.attachedNormal = n;
}

GBA_API gba_group* gba_group_create(int players) {
	if (players < 1 || players > MAX_GBAS) {
		return NULL;
	}
	struct gba_group* g = calloc(1, sizeof(*g));
	if (!g) {
		return NULL;
	}
	mLockstepInit(&g->ls.d);
	GBASIOLockstepInit(&g->ls);
	g->ls.d.context = g;
	g->ls.d.signal = cbSignal;
	g->ls.d.wait = cbWait;
	g->ls.d.addCycles = cbAddCycles;
	g->ls.d.useCycles = cbUseCycles;
	g->ls.d.unusedCycles = cbUnusedCycles;
	g->ls.d.unload = cbUnload;
	g->expected = players;
	int i;
	for (i = 0; i < MAX_GBAS; ++i) {
		g->p[i].awake = 1;
	}
	return g;
}

/* ---- trailer: the lockstep state of one member, appended to its save state ---- */

#define TR_MAGIC 0x314B4C47u /* "GLK1" */

static bool grpTrailerValid(const uint8_t* t) {
	return get32(t) == TR_MAGIC;
}

static size_t grpTrailer(gba_core* c) {
	return c->group ? GRP_TRAILER : 0;
}

static void grpSaveTrailer(gba_core* c, uint8_t* out) {
	struct gba_group* g = c->group;
	int i = c->gidx;
	struct GBASIOLockstepNode* node = &g->nodes[i];
	uint32_t w[28];
	int k = 0, j;
	memset(w, 0, sizeof(w));
	w[k++] = TR_MAGIC;
	w[k++] = (uint32_t) i;
	w[k++] = (uint32_t) g->ls.d.transferActive;
	w[k++] = (uint32_t) g->ls.d.transferCycles;
	for (j = 0; j < 4; ++j) {
		w[k++] = g->ls.multiRecv[j];
	}
	for (j = 0; j < 4; ++j) {
		w[k++] = g->ls.normalRecv[j];
	}
	w[k++] = (uint32_t) node->nextEvent;
	w[k++] = (uint32_t) node->eventDiff;
	w[k++] = node->normalSO ? 1 : 0;
	w[k++] = (uint32_t) (int32_t) node->mode;
	w[k++] = node->transferFinished ? 1 : 0;
	bool sched = mTimingIsScheduled(&c->gba->timing, &node->event);
	w[k++] = sched ? 1 : 0;
	w[k++] = sched ? (uint32_t) mTimingUntil(&c->gba->timing, &node->event) : 0;
	w[k++] = (uint32_t) g->p[i].awake;
	w[k++] = (uint32_t) g->p[i].posted;
	w[k++] = g->p[i].waitMask;
	w[k++] = c->gba->sio.siocnt; /* live SIOCNT / RCNT (the lockstep edits them; a state only holds the raw IO copies) */
	w[k++] = c->gba->sio.rcnt;
	w[k++] = c->gba->memory.io[REG_SIOMLT_SEND >> 1]; /* write-only registers are not part of an mGBA state */
	for (j = 0; j < k; ++j) {
		put32(out + 4 * j, w[j]);
	}
}

static void grpApplyTrailer(gba_core* c, const uint8_t* t, bool withGroupFields) {
	struct gba_group* g = c->group;
	int i = c->gidx;
	struct GBASIOLockstepNode* node = &g->nodes[i];
	int k = 2, j;
	uint32_t ta = get32(t + 4 * k++);
	uint32_t tc = get32(t + 4 * k++);
	uint32_t mr[4], nr[4];
	for (j = 0; j < 4; ++j) {
		mr[j] = get32(t + 4 * k++);
	}
	for (j = 0; j < 4; ++j) {
		nr[j] = get32(t + 4 * k++);
	}
	if (withGroupFields) {
		g->ls.d.transferActive = (enum mLockstepPhase) ta;
		g->ls.d.transferCycles = (int32_t) tc;
		for (j = 0; j < 4; ++j) {
			g->ls.multiRecv[j] = (uint16_t) mr[j];
			g->ls.normalRecv[j] = nr[j];
		}
	}
	node->nextEvent = (int32_t) get32(t + 4 * k++);
	node->eventDiff = (int32_t) get32(t + 4 * k++);
	node->normalSO = get32(t + 4 * k++) != 0;
	node->mode = (enum GBASIOMode) (int32_t) get32(t + 4 * k++);
	node->transferFinished = get32(t + 4 * k++) != 0;
	bool sched = get32(t + 4 * k++) != 0;
	int32_t delta = (int32_t) get32(t + 4 * k++);
	mTimingDeschedule(&c->gba->timing, &node->event);
	if (sched) {
		mTimingSchedule(&c->gba->timing, &node->event, delta < 0 ? 0 : delta);
	}
	g->p[i].awake = (int) get32(t + 4 * k++);
	g->p[i].posted = (int32_t) get32(t + 4 * k++);
	g->p[i].waitMask = get32(t + 4 * k++);
	c->gba->sio.siocnt = (uint16_t) get32(t + 4 * k++);
	c->gba->sio.rcnt = (uint16_t) get32(t + 4 * k++);
	c->gba->memory.io[REG_SIOMLT_SEND >> 1] = (uint16_t) get32(t + 4 * k++);
	grpRecount(g);
}

/* A core without usable lockstep state (fresh join, reset, state without a trailer): idle node in the core's current mode. */
static void grpFreshNode(gba_core* c) {
	struct gba_group* g = c->group;
	int i = c->gidx;
	struct GBASIOLockstepNode* node = &g->nodes[i];
	mTimingDeschedule(&c->gba->timing, &node->event);
	node->nextEvent = 0;
	node->eventDiff = 0;
	node->normalSO = true;
	node->transferFinished = true;
	node->mode = c->gba->sio.mode;
	if (grpLinkMode((int) node->mode)) {
		mTimingSchedule(&c->gba->timing, &node->event, 0);
	}
	g->p[i].awake = 1;
	g->p[i].posted = 0;
	g->p[i].waitMask = 0;
	if (i == 0) {
		g->ls.d.transferActive = TRANSFER_IDLE;
	}
	grpRecount(g);
}

/* mGBA's state load leaves cpu->nextEvent at the value stored in the state, which for a live core is "distance to the next
 * timing event" but is not recomputed on load; make it exact so a reloaded core is bit-identical to the one it was saved from. */
static void grpFixNextEvent(gba_core* c) {
	struct mTiming* t = &c->gba->timing;
	if (t->reroot) {
		t->root = t->reroot;
		t->reroot = NULL;
	}
	if (t->root) {
		((struct ARMCore*) c->core->cpu)->nextEvent = mTimingNextEvent(t) + *t->relativeCycles;
	}
}

static void grpBeforeLoad(gba_core* c) {
	struct GBASIOLockstepNode* node = &c->group->nodes[c->gidx];
	node->transferFinished = true; /* a mode switch during the load must not flush the old transfer into the new state */
	mTimingDeschedule(&c->gba->timing, &node->event);
}

static void grpAfterLoad(gba_core* c, const uint8_t* trailer) {
	if (trailer) {
		grpApplyTrailer(c, trailer, c->gidx == 0);
	} else {
		grpFreshNode(c);
	}
	grpFixNextEvent(c);
}

/* ---- joining / leaving ---- */

GBA_API int gba_group_add(gba_group* g, gba_core* c) {
	if (!g || !c || !c->romLoaded || c->platform != GBA_PLATFORM_GBA || c->group || g->dissolved || g->added >= g->expected) {
		return -1;
	}
	gba_link_configure(c, 0, 1); /* the classic cable is off while the core is in a group */
	int i = g->added;
	struct GBASIOLockstepNode* node = &g->nodes[i];
	memset(node, 0, sizeof(*node));
	node->mode = SIO_GPIO;
	GBASIOLockstepNodeCreate(node);
	if (!GBASIOLockstepAttachNode(&g->ls, node)) {
		return -2;
	}
	g->cores[i] = c;
	g->added = i + 1;
	c->group = g;
	c->gidx = i;
	if (!c->shown) {
		c->shown = calloc((size_t) GBA_WIDTH * GBA_HEIGHT, sizeof(color_t));
	}
	if (c->shown) {
		memcpy(c->shown, c->video, (size_t) GBA_WIDTH * GBA_HEIGHT * sizeof(color_t));
	}
	struct GBASIO* sio = &c->gba->sio;
	GBASIOSetDriver(sio, &node->d, SIO_MULTI);
	GBASIOSetDriver(sio, &node->d, SIO_NORMAL_32);
	if (g->added == g->expected) {
		/* everybody is in: restore the lockstep state carried by the states they were loaded from, if all have it */
		bool all = true;
		for (i = 0; i < g->added; ++i) {
			all = all && g->cores[i]->stash;
		}
		if (all) {
			for (i = 0; i < g->added; ++i) {
				grpApplyTrailer(g->cores[i], g->cores[i]->stash, i == 0);
			}
		}
		for (i = 0; i < g->added; ++i) {
			free(g->cores[i]->stash);
			g->cores[i]->stash = NULL;
		}
		grpRecount(g);
		for (i = 0; i < g->added; ++i) {
			grpFixNextEvent(g->cores[i]);
		}
	}
	return 0;
}

static void grpRelease(struct gba_group* g, int i) {
	gba_core* c = g->cores[i];
	if (!c) {
		return;
	}
	struct GBASIOLockstepNode* node = &g->nodes[i];
	struct GBASIO* s = &c->gba->sio;
	struct gba_link* L = &c->link;
	mTimingDeschedule(&c->gba->timing, &node->event);
	s->drivers.multiplayer = &L->dMulti.d;
	s->drivers.normal = &L->dNormal.d;
	if (s->activeDriver == &node->d) {
		int mode = (int) s->mode;
		s->activeDriver = mode == SIO_MULTI ? &L->dMulti.d : (mode == SIO_NORMAL_8 || mode == SIO_NORMAL_32) ? &L->dNormal.d : NULL;
		if (s->activeDriver) {
			s->activeDriver->p = s;
		}
	}
	c->group = NULL;
	g->cores[i] = NULL;
	free(c->shown);
	c->shown = NULL;
	linkReset(L); /* clears a busy bit left behind and refreshes the cable bits for the classic driver */
}

static void grpDissolve(struct gba_group* g) {
	int i;
	for (i = 0; i < g->added; ++i) {
		grpRelease(g, i);
	}
	g->dissolved = true;
}

/* a member is destroyed or reloaded: the whole group falls apart (see gbacore.h) */
static void grpDetach(gba_core* c) {
	grpDissolve(c->group);
}

GBA_API void gba_group_destroy(gba_group* g) {
	if (!g) {
		return;
	}
	grpDissolve(g);
	free(g);
}

/* ---- running ---- */

GBA_API int gba_group_run_frame(gba_group* g) {
	if (!g || g->dissolved || g->added < 1) {
		return GBA_RUN_ERROR;
	}
	int n = g->added, i;
	uint32_t start[MAX_GBAS];
	bool done[MAX_GBAS];
	for (i = 0; i < n; ++i) {
		if (!g->cores[i] || !g->cores[i]->romLoaded) {
			return GBA_RUN_ERROR;
		}
		start[i] = g->cores[i]->gba->video.frameCounter;
		done[i] = false;
	}
	long rounds = 0, idle = 0;
	for (;;) {
		bool all = true;
		for (i = 0; i < n; ++i) {
			all = all && done[i];
		}
		if (all) {
			break;
		}
		if (++rounds > 4000000) {
			return GBA_RUN_ERROR;
		}
		bool stepped = false;
		for (i = 0; i < n; ++i) {
			if (g->p[i].awake < 1) {
				continue;
			}
			if (done[i] && i != 0 && !(g->p[0].awake < 1 && (g->p[0].waitMask & (1u << i)))) {
				continue; /* finished its frame: only runs on when the master waits for it */
			}
			gba_core* c = g->cores[i];
			ARMRunLoop(c->core->cpu);
			stepped = true;
			if (!done[i] && c->gba->video.frameCounter != start[i]) {
				done[i] = true;
				finishFrame(c);
				memcpy(c->shown, c->video, (size_t) GBA_WIDTH * GBA_HEIGHT * sizeof(color_t));
			}
		}
		if (!stepped) {
			/* nobody can run (should not happen): wake everybody so the frame still completes */
			++g->stalls;
			if (++idle > 16) {
				return GBA_RUN_ERROR;
			}
			for (i = 0; i < n; ++i) {
				g->p[i].awake = 1;
				g->p[i].waitMask = 0;
				if (g->p[i].posted <= 0) {
					g->p[i].posted = 2000;
				}
			}
		} else {
			idle = 0;
		}
	}
	for (i = 0; i < n; ++i) {
		drainAudio(g->cores[i]);
	}
	return GBA_RUN_FRAME_DONE;
}

/* test hook: one scheduler round without frame bookkeeping; returns the bitmask of cores that were stepped */
int gba_priv_group_round(gba_group* g) {
	int i, m = 0;
	for (i = 0; i < g->added; ++i) {
		if (g->p[i].awake < 1) {
			continue;
		}
		ARMRunLoop(g->cores[i]->core->cpu);
		m |= 1 << i;
	}
	return m;
}

GBA_API long gba_group_stalls(gba_group* g) {
	return g ? g->stalls : 0;
}

/* ---- virtual RTC ---- */

/* Game Boy: the cartridge remembers the UNIX time of its last latch (taken at ROM load from the then-current clock);
 * keep it in step when the clock source changes so no phantom time passes. A saved clock loaded afterwards replaces it. */
static void gbRtcShift(gba_core* c, int64_t before) {
	if (c->gb) {
		struct mRTCSource* r = c->gb->memory.rtc;
		if (r) {
			c->gb->memory.rtcLastLatch += (time_t) ((int64_t) r->unixTime(r) - before);
		}
	}
}

GBA_API int gba_set_rtc(gba_core* c, int64_t epoch_seconds, int mode) {
	if (!c) {
		return -1;
	}
	struct mRTCGenericSource* rtc = &c->core->rtc;
	int64_t before = c->gb ? gba_priv_rtc_unix(c) : 0;
	if (mode == GBA_RTC_VIRTUAL) {
		if (!c->rtcVirtual) {
			tzAcquire();
			c->rtcVirtual = true;
		}
		rtc->override = RTC_FAKE_EPOCH;
		rtc->value = 0;
		rtc->value = epoch_seconds * 1000LL - rtcFakeMs(c->core);
		gbRtcShift(c, before);
		return 0;
	}
	if (mode == GBA_RTC_WALL) {
		if (c->rtcVirtual) {
			c->rtcVirtual = false;
			rtc->override = RTC_NO_OVERRIDE;
			rtc->value = 0;
			tzRelease();
			rtcRebaseOffset(c);
			gbRtcShift(c, before);
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
	struct mRTCSource* r = c->gba ? c->gba->rtcSource : c->gb->memory.rtc;
	return r ? (int64_t) r->unixTime(r) : 0;
}
int gba_priv_link_waiting(gba_core* c) {
	if (c->platform != GBA_PLATFORM_GBA) {
		return 0;
	}
	return c->link.mstate == M_WAIT || c->link.cstate == C_BUSY;
}
