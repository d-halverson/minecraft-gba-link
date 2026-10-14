package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Input-sync netplay for one client, free of Minecraft classes so it can be tested headless. Every client simulates ALL
 * linked consoles ({@link NetplaySim}); only controller records cross the network.
 *
 * <p>Protocol summary (see SPEC.md "Netplay link"): frame f is run when the records of all players for f are known. At
 * frame s each player samples its keys and sends the record for frame s + delay (records for frames below the initial
 * delay are implicit zeros). A record is a short: keys (bits 0-9) and the sender's delay proposal (bits 10-13). The delay
 * every player uses is the maximum of the proposals found in the records of the frame just simulated, so all clients
 * switch at the same simulated frame without any extra messages. Every 60th frame the clients exchange a hash of all
 * consoles; on a mismatch player 0 snapshots all consoles and everybody reloads them into fresh cores (resync).
 *
 * <p>Threading: {@code on*} methods may be called from any thread; everything else on the emulator thread.
 */
public final class NetplayEngine implements AutoCloseable {
	public static final int HASH_INTERVAL = 60, RING = 1024, REDUNDANCY = 3;
	public static final long CONNECT_TIMEOUT_NANOS = 15_000_000_000L, STALL_TIMEOUT_NANOS = 10_000_000_000L, RESYNC_TIMEOUT_NANOS = 10_000_000_000L;
	private static final int CHUNK = 30000;

	public enum State { CONNECTING, RUNNING, FAILED }
	public enum Step { RAN, STALLED, IDLE }

	/** Session parameters from the server's netplay_start. {@code epoch} is the agreed virtual RTC (UTC seconds). */
	public record Params(long sessionId, int index, int count, List<String> names, long epoch, int tzOffset, int delay) {}

	public interface Transport {
		void input(long session, int firstFrame, short[] recs, int sendMs, int[] echo);
		void hash(long session, int gen, int frame, int hash);
		/** Send a snapshot blob; the transport splits it into chunks of at most 30000 bytes. */
		void snapshot(long session, int gen, int frame, byte[] blob);
		void restart(long session);
	}

	public interface Listener {
		void log(String message);
		/** A hash mismatch was found for this frame (player 0 resyncs; the others wait for its snapshot). */
		default void desync(int frame, int gen) {}
		/** Fresh cores were loaded from a resync snapshot. */
		default void resynced(int frame, int gen) {}
	}

	private final GbaNative natives;
	private final Path rom;
	private final Params p;
	private final Transport tx;
	private final LongSupplier clock;
	private final Listener ls;
	private final long t0;
	private final int n, me, startDelay;
	private final LinkedBlockingQueue<Runnable> inbox = new LinkedBlockingQueue<>();

	private volatile State state = State.CONNECTING;
	private volatile String failure = "";
	private NetplaySim sim;
	private volatile GbaCore localCore;

	// frame state
	private volatile int frame;
	private int gen, curDelay = 0, lastSampled = -1, nextTarget, catchUpTo;
	private final short[][] rec;
	private final int[][] recFrame;
	private final DelayModel delayModel;
	private final int[] keyBuf;

	// echo (round trip) bookkeeping
	private final int[] lastPeerSend;
	private final long[] lastPeerRecv;
	private final boolean[] heard;
	private long lastKeepalive;

	// stalls
	private boolean stalling;
	private long stallStart;

	// snapshots
	private final Map<Long, byte[]> assembling = new HashMap<>();
	private final Map<Long, int[]> assembledGot = new HashMap<>();
	private final byte[][] initial;
	private byte[] ownBlob;
	private byte[] resyncBlob;
	private int resyncGen;
	private long resyncWaitSince;
	private final long connectStart;

	// hashes
	private final Map<Long, int[]> hashes = new HashMap<>();
	private boolean desyncReported;

	// stats
	public volatile long totalFrames, totalStalls, totalStallNanos, desyncs, resyncs;
	private long winStart, winFrames, winStalls;
	private volatile double fps, stallsPerSec;

	public NetplayEngine(GbaNative natives, Path rom, Params p, Transport tx, LongSupplier clock, Listener ls) {
		this.natives = natives;
		this.rom = rom;
		this.p = p;
		this.tx = tx;
		this.clock = clock;
		this.ls = ls;
		this.n = p.count();
		this.me = p.index();
		this.startDelay = DelayModel.clamp(p.delay());
		this.curDelay = startDelay;
		this.nextTarget = startDelay;
		this.t0 = clock.getAsLong();
		this.connectStart = t0;
		this.rec = new short[n][RING];
		this.recFrame = new int[n][RING];
		for (int[] a : recFrame) Arrays.fill(a, -1);
		this.keyBuf = new int[n];
		this.lastPeerSend = new int[n];
		this.lastPeerRecv = new long[n];
		this.heard = new boolean[n];
		this.initial = new byte[n][];
		this.delayModel = new DelayModel(n, startDelay, 8, t0);
		this.winStart = t0;
	}

	// ------------------------------------------------------------------ session start

	/** Emulator thread: hand over the local console's snapshot (taken at a frame boundary); it is sent to the others. */
	public void begin(byte[] state, byte[] save) {
		NetplaySnapshot s = new NetplaySnapshot(0, startDelay, new int[] {me}, new byte[][] {state}, new byte[][] {save});
		ownBlob = s.encode();
		initial[me] = ownBlob;
		tx.snapshot(p.sessionId(), 0, 0, ownBlob);
		ls.log("netplay session " + p.sessionId() + ": P" + (me + 1) + " of " + n + ", snapshot " + ownBlob.length + " bytes sent");
	}

	public Params params() { return p; }
	public State state() { return state; }
	public String failure() { return failure; }
	public int frame() { return frame; }
	public int delay() { return curDelay; }
	public int generation() { return gen; }
	public double pingMs() { return delayModel.pingMs(); }
	public double fps() { return fps; }
	public double stallsPerSec() { return stallsPerSec; }
	public GbaCore localCore() { return localCore; }
	public NetplaySim sim() { return sim; }
	/** True while replaying frames after a resync (the caller should not wait for the frame clock). */
	public boolean catchingUp() { return state == State.RUNNING && frame < catchUpTo; }
	public boolean stalled() { return stalling; }

	private void fail(String why) {
		if (state == State.FAILED) return;
		state = State.FAILED;
		failure = why;
		ls.log("netplay failed: " + why);
	}

	// ------------------------------------------------------------------ inbound (any thread)

	public void onInput(long session, int from, int firstFrame, short[] recs, int sendMs, int[] echo) {
		if (session != p.sessionId()) return;
		long now = clock.getAsLong();
		inbox.add(() -> handleInput(from, firstFrame, recs, sendMs, echo, now));
	}

	public void onHash(long session, int from, int gen, int frame, int hash) {
		if (session != p.sessionId()) return;
		inbox.add(() -> storeHash(gen, frame, from, hash));
	}

	public void onSnapshot(long session, int from, int gen, int frame, int offset, int total, byte[] bytes) {
		if (session != p.sessionId()) return;
		inbox.add(() -> handleChunk(from, gen, frame, offset, total, bytes));
	}

	// ------------------------------------------------------------------ emulator thread

	private int nowMs(long now) { return (int) ((now - t0) / 1_000_000L); }

	/** Drain what the network delivered; start/resync when the snapshots are complete. */
	public void pump() { pump(0); }

	/** Like {@link #pump()}, but waits up to {@code waitNanos} for the first message (wakes as soon as one arrives). */
	public void pump(long waitNanos) {
		Runnable r = null;
		try {
			r = waitNanos > 0 ? inbox.poll(waitNanos, TimeUnit.NANOSECONDS) : inbox.poll();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		for (; r != null; r = inbox.poll()) {
			try {
				r.run();
			} catch (RuntimeException e) {
				fail("netplay error: " + e);
			}
		}
		long now = clock.getAsLong();
		try {
			if (state == State.CONNECTING) {
				boolean all = true;
				for (byte[] b : initial) if (b == null) all = false;
				if (all) startSim();
				else if (now - connectStart > CONNECT_TIMEOUT_NANOS) fail("Link connection timed out");
			} else if (state == State.RUNNING) {
				if (resyncBlob != null) applyResync();
				else if (resyncWaitSince != 0 && now - resyncWaitSince > RESYNC_TIMEOUT_NANOS) {
					resyncWaitSince = 0;
					ls.log("resync snapshot did not arrive - asking the server to restart the session");
					tx.restart(p.sessionId());
				}
			}
		} catch (RuntimeException e) {
			fail("netplay error: " + e);
		}
	}

	private NetplaySim build(int[] players, byte[][] states, byte[][] saves, int atFrame) {
		long epoch = p.epoch() + p.tzOffset() + ((long) atFrame * 280896L) / 16777216L;
		GbaCore[] cs = new GbaCore[n];
		try {
			for (int k = 0; k < players.length; k++) {
				int i = players[k];
				GbaCore c = new GbaCore(natives);
				cs[i] = c;
				if (c.loadRom(rom) < 0) throw new IllegalStateException("could not load the ROM");
				if (!c.setRtc(epoch, GbaNative.RTC_VIRTUAL)) throw new IllegalStateException("virtual RTC unavailable");
				if (saves[k].length > 0 && c.loadSave(saves[k]) < 0) ls.log("core rejected the save of P" + (i + 1));
				if (!c.loadState(states[k])) throw new IllegalStateException("could not load the state of P" + (i + 1));
			}
			for (GbaCore c : cs) if (c == null) throw new IllegalStateException("incomplete snapshot");
		} catch (RuntimeException e) {
			for (GbaCore c : cs) if (c != null) c.close();
			throw e;
		}
		try {
			return new NetplaySim(cs, me);
		} catch (RuntimeException e) {
			for (GbaCore c : cs) c.close();
			throw e;
		}
	}

	private void startSim() {
		int[] players = new int[n];
		byte[][] states = new byte[n][], saves = new byte[n][];
		for (int i = 0; i < n; i++) {
			NetplaySnapshot s = NetplaySnapshot.decode(initial[i]);
			if (s == null || s.players().length != 1 || s.players()[0] != i) { fail("bad snapshot from P" + (i + 1)); return; }
			players[i] = i;
			states[i] = s.states()[0];
			saves[i] = s.saves()[0];
		}
		sim = build(players, states, saves, 0);
		localCore = sim.localCore();
		frame = 0;
		state = State.RUNNING;
		winStart = clock.getAsLong();
		ls.log("netplay running: " + n + " consoles, initial delay " + startDelay + "f");
	}

	private static int rk(short r) { return r & 0x3FF; }
	private static int rp(short r) { return (r >> 10) & 0xF; }
	private static short pack(int keys, int proposal) { return (short) ((keys & 0x3FF) | (proposal & 0xF) << 10); }

	/** The record of {@code player} for frame {@code f}, or -1 if it is not known (yet). */
	private int get(int player, int f) {
		if (f < startDelay) return pack(0, startDelay);
		int i = f & (RING - 1);
		return recFrame[player][i] == f ? rec[player][i] & 0xFFFF : -1;
	}

	private void put(int player, int f, short r) {
		int i = f & (RING - 1);
		if (recFrame[player][i] == f) return; // first record wins: inputs are immutable once known
		recFrame[player][i] = f;
		rec[player][i] = r;
	}

	private void handleInput(int from, int firstFrame, short[] recs, int sendMs, int[] echo, long arrival) {
		if (from < 0 || from >= n || from == me) return;
		for (int i = 0; i < recs.length; i++) {
			int f = firstFrame + i;
			if (f >= frame - RING / 2 && f < frame + RING / 2) put(from, f, recs[i]);
		}
		lastPeerSend[from] = sendMs;
		lastPeerRecv[from] = arrival;
		heard[from] = true;
		int nm = nowMs(arrival);
		for (int i = 0; i + 2 < echo.length; i += 3) {
			if (echo[i] == me) delayModel.addRtt(from, nm - echo[i + 1] - echo[i + 2], arrival);
		}
	}

	private void sendInput(long now) {
		int last = nextTarget - 1;
		int first = Math.max(startDelay, last - REDUNDANCY + 1);
		if (last < first) return;
		short[] out = new short[last - first + 1];
		for (int f = first; f <= last; f++) {
			int r = get(me, f);
			out[f - first] = (short) (r < 0 ? 0 : r);
		}
		int ne = 0;
		int[] echo = new int[3 * n];
		for (int j = 0; j < n; j++) {
			if (j == me || !heard[j]) continue;
			echo[ne++] = j;
			echo[ne++] = lastPeerSend[j];
			echo[ne++] = Math.max(0, nowMs(now) - nowMs(lastPeerRecv[j]));
		}
		tx.input(p.sessionId(), first, out, nowMs(now), Arrays.copyOf(echo, ne));
		lastKeepalive = now;
	}

	private void sample(IntSupplier keys, long now) {
		int s = frame;
		lastSampled = s;
		int target = s + curDelay;
		short r = pack(keys.getAsInt(), delayModel.proposal());
		for (int t = nextTarget; t <= target; t++) put(me, t, r); // raising the delay fills the gap, lowering skips samples
		if (target + 1 > nextTarget) nextTarget = target + 1;
		sendInput(now);
	}

	/**
	 * Try to run the next frame. {@code keys} is read once per frame (when the frame is first attempted). Returns RAN when a
	 * frame was simulated, STALLED when the other players' records are missing, IDLE when not running.
	 */
	public Step advance(IntSupplier keys) {
		if (state != State.RUNNING) return Step.IDLE;
		long now = clock.getAsLong();
		delayModel.update(now);
		if (frame > lastSampled) sample(keys, now);
		int max = 0;
		boolean missing = false;
		for (int i = 0; i < n; i++) {
			int r = get(i, frame);
			if (r < 0) { missing = true; break; }
			keyBuf[i] = rk((short) r);
			max = Math.max(max, rp((short) r));
		}
		if (missing) {
			if (!stalling) {
				stalling = true;
				stallStart = now;
				totalStalls++;
				winStalls++;
				delayModel.onStall(now, curDelay);
			} else if (now - stallStart > STALL_TIMEOUT_NANOS) {
				fail("Link partner not responding");
				return Step.IDLE;
			} else if (now - lastKeepalive > 100_000_000L) {
				sendInput(now);
			}
			return Step.STALLED;
		}
		if (stalling) {
			stalling = false;
			totalStallNanos += now - stallStart;
		}
		boolean ok;
		try {
			ok = sim.tick(keyBuf);
		} catch (RuntimeException e) {
			fail("netplay simulation error: " + e);
			return Step.IDLE;
		}
		if (!ok) { fail("The emulator core reported an error"); return Step.IDLE; }
		int done = frame++;
		if (max >= DelayModel.MIN) curDelay = Math.min(DelayModel.MAX, max);
		totalFrames++;
		winFrames++;
		if (done % HASH_INTERVAL == HASH_INTERVAL - 1) hashCheck(done);
		rollStats(now);
		return Step.RAN;
	}

	private void rollStats(long now) {
		long dt = now - winStart;
		if (dt >= 1_000_000_000L) {
			fps = winFrames * 1e9 / dt;
			stallsPerSec = winStalls * 1e9 / dt;
			winFrames = winStalls = 0;
			winStart = now;
		}
	}

	// ------------------------------------------------------------------ hashes and resync

	private static long hkey(int gen, int frame) { return ((long) gen << 32) | (frame & 0xFFFFFFFFL); }

	private void hashCheck(int doneFrame) {
		int h = sim.hashAll();
		storeHash(gen, doneFrame, me, h);
		tx.hash(p.sessionId(), gen, doneFrame, h);
	}

	private void storeHash(int g, int f, int from, int h) {
		if (from < 0 || from >= n) return;
		long k = hkey(g, f);
		int[] a = hashes.computeIfAbsent(k, x -> {
			int[] v = new int[n * 2];
			return v; // [player] = hash, [n + player] = 1 if present
		});
		a[from] = h;
		a[n + from] = 1;
		if (hashes.size() > 64) { // drop the oldest entries
			long oldest = Long.MAX_VALUE;
			for (long kk : hashes.keySet()) oldest = Math.min(oldest, kk);
			hashes.remove(oldest);
		}
		if (g != gen || state != State.RUNNING || a[n + me] == 0) return;
		for (int j = 0; j < n; j++) {
			if (j != me && a[n + j] == 1 && a[j] != a[me]) {
				desyncs++;
				ls.log("DESYNC at frame " + f + " (gen " + g + "): P" + (me + 1) + " hash " + Integer.toHexString(a[me]) + " vs P" + (j + 1) + " " + Integer.toHexString(a[j]));
				ls.desync(f, g);
				if (me == 0 && gen == g) startResync();
				return;
			}
		}
	}

	/** Player 0: snapshot every console now and reload everybody (including this client) from it. */
	private void startResync() {
		int[] players = new int[n];
		byte[][] states = new byte[n][], saves = new byte[n][];
		for (int i = 0; i < n; i++) {
			players[i] = i;
			states[i] = sim.core(i).saveState();
			saves[i] = sim.core(i).getSave();
			if (saves[i] == null) saves[i] = new byte[0];
		}
		int g = gen + 1;
		NetplaySnapshot snap = new NetplaySnapshot(frame, curDelay, players, states, saves);
		byte[] blob = snap.encode();
		gen = g;
		tx.snapshot(p.sessionId(), g, frame, blob);
		ls.log("resync: player 0 sent a snapshot of all consoles at frame " + frame + " (gen " + g + ", " + blob.length + " bytes)");
		resyncBlob = blob;
		resyncGen = g;
	}

	private void applyResync() {
		byte[] blob = resyncBlob;
		resyncBlob = null;
		resyncWaitSince = 0;
		NetplaySnapshot s = NetplaySnapshot.decode(blob);
		if (s == null || s.players().length != n) { fail("bad resync snapshot"); return; }
		NetplaySim fresh = build(s.players(), s.states(), s.saves(), s.frame());
		GbaCore oldLocal = localCore;
		NetplaySim old = sim;
		int oldFrame = frame;
		sim = fresh;
		localCore = fresh.localCore();
		if (old != null) old.close();
		gen = resyncGen;
		frame = s.frame();
		curDelay = DelayModel.clamp(s.delay());
		catchUpTo = Math.max(catchUpTo, oldFrame);
		hashes.clear();
		stalling = false;
		resyncs++;
		ls.resynced(frame, gen);
		ls.log("resync applied: reloaded " + n + " consoles at frame " + frame + " (was at " + oldFrame + ", gen " + gen + ")");
	}

	private void handleChunk(int from, int g, int f, int offset, int total, byte[] bytes) {
		if (from < 0 || from >= n || total <= 0 || total > com.drew.gba.net.Payloads.MAX_NETPLAY_SNAPSHOT || offset < 0 || offset + bytes.length > total) return;
		long key = ((long) g << 8) | from;
		if (g > 0 && from != 0) return; // resync snapshots only come from player 0
		if (g > 0 && (g <= gen)) return; // stale
		if (g == 0 && state != State.CONNECTING) return;
		byte[] buf = assembling.get(key);
		int[] got = assembledGot.get(key);
		if (buf == null || buf.length != total || offset == 0) {
			buf = new byte[total];
			got = new int[] {0};
			assembling.put(key, buf);
			assembledGot.put(key, got);
		}
		System.arraycopy(bytes, 0, buf, offset, bytes.length);
		got[0] += bytes.length;
		if (got[0] >= total) {
			assembling.remove(key);
			assembledGot.remove(key);
			if (g == 0) initial[from] = buf;
			else {
				resyncBlob = buf;
				resyncGen = g;
			}
		} else if (g > 0 && resyncWaitSince == 0) {
			resyncWaitSince = clock.getAsLong();
		}
	}

	// ------------------------------------------------------------------ UI strings

	public String statusLine() {
		if (state == State.CONNECTING) return "Connecting link...";
		String ping = delayModel.pingMs() > 0 ? " · ping " + Math.round(delayModel.pingMs()) + " ms" : "";
		return "Link: P" + (me + 1) + " of " + n + " · netplay · delay " + curDelay + "f" + ping;
	}

	public String statsLine() {
		if (state != State.RUNNING) return "";
		return String.format(java.util.Locale.ROOT, "Netplay: %.1f fps · stalls %.1f/s · delay %df · desyncs %d", fps, stallsPerSec, curDelay, desyncs);
	}

	/** Leave netplay: closes the other consoles and returns the local console (state kept), or null if it never started. */
	public GbaCore release() {
		if (sim == null) return null;
		GbaCore c = sim.releaseLocal();
		sim = null;
		localCore = null;
		return c;
	}

	@Override
	public void close() {
		if (sim != null) { sim.close(); sim = null; }
		localCore = null;
	}

	/** Test hook: inject a fault into the local console (flips a word in its work RAM through a save-state round trip). */
	public boolean debugCorrupt() {
		GbaCore c = localCore;
		if (c == null) return false;
		byte[] st = c.saveState();
		st[0x21000 + 0x100] ^= 0x5A; // EWRAM
		st[0x21000 + 0x101] ^= 0xA5;
		return c.loadState(st);
	}
}
