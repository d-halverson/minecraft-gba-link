package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;

import java.util.ArrayDeque;
import java.util.zip.CRC32;

/**
 * All consoles of a link (2 to 4) in ONE thread, link messages routed between them with zero latency in a fixed,
 * deterministic order. Every machine runs the same simulation from the same inputs, so only controller input has to
 * cross the network (see NetplayEngine). Generalises the proven two-console algorithm of the feasibility spike:
 *
 * <p>Tick rule (identical on every machine, any change must be versioned with the protocol): core 0 (the master) runs;
 * whenever it stops in LINK_WAIT its pending messages go to all other cores in index order, their immediate replies are
 * delivered back, and only if the master is still stuck do the other cores run their own frame (index order, after each
 * the master gets its replies). Cores that have not run when the master finishes the frame run then, in index order.
 * Finally everything in flight is delivered so the state can be snapshotted between ticks.
 */
public final class NetplaySim implements AutoCloseable {
	private record Msg(int from, byte[] data) {}

	private final GbaCore[] cores;
	private final int n, local;
	private final ArrayDeque<Msg>[] inbox;
	private final short[] pcm = new short[2048 * 2];
	public long linkWaits, relayedMsgs, childRuns, ticks;

	@SuppressWarnings("unchecked")
	public NetplaySim(GbaCore[] cores, int local) {
		this.cores = cores;
		this.n = cores.length;
		this.local = local;
		this.inbox = new ArrayDeque[n];
		for (int i = 0; i < n; i++) inbox[i] = new ArrayDeque<>();
		for (int i = 0; i < n; i++) pump(i);
	}

	public int players() { return n; }
	public int localIndex() { return local; }
	public GbaCore core(int i) { return cores[i]; }
	public GbaCore localCore() { return cores[local]; }

	/** Moves the core's outgoing messages into every other core's inbox. */
	private void pump(int i) {
		byte[] m;
		while ((m = cores[i].linkPollOutgoing()) != null) {
			relayedMsgs++;
			for (int j = 0; j < n; j++) if (j != i) inbox[j].add(new Msg(i, m));
		}
	}

	private void deliver(int i) {
		Msg m;
		while ((m = inbox[i].poll()) != null) cores[i].linkDeliver(m.from, m.data);
		pump(i);
	}

	private boolean othersHaveMail() {
		for (int j = 1; j < n; j++) if (!inbox[j].isEmpty()) return true;
		return false;
	}

	private void runChild(int i) {
		deliver(i);
		int r = cores[i].runFrame();
		childRuns++;
		if (r == GbaNative.RUN_LINK_WAIT) throw new IllegalStateException("child core blocked");
		if (r == GbaNative.RUN_ERROR) throw new IllegalStateException("child core error");
		pump(i);
		deliver(0);
	}

	/** Advance every console by one frame with the given keys (one mask per player). Returns false on a core error. */
	public boolean tick(int[] keys) {
		for (int i = 0; i < n; i++) cores[i].setKeys(keys[i]);
		boolean[] ran = new boolean[n];
		int stalls = 0;
		while (true) {
			int r = cores[0].runFrame();
			pump(0);
			if (r == GbaNative.RUN_ERROR) return false;
			if (r == GbaNative.RUN_FRAME_DONE) break;
			linkWaits++;
			boolean fresh = othersHaveMail();
			for (int j = 1; j < n; j++) deliver(j);
			deliver(0);
			if (!fresh) {
				if (++stalls > 64) throw new IllegalStateException("master stuck in LINK_WAIT");
				for (int j = 1; j < n; j++) { runChild(j); ran[j] = true; }
			}
		}
		for (int j = 1; j < n; j++) if (!ran[j]) runChild(j);
		for (int j = 0; j < n; j++) if (j != local) while (cores[j].readAudio(pcm, 2048) > 0) { } // other consoles are silent
		for (int i = 0; i < 8; i++) {
			boolean any = false;
			for (int j = 0; j < n; j++) if (!inbox[j].isEmpty()) any = true;
			if (!any) break;
			for (int j = 0; j < n; j++) deliver(j);
		}
		ticks++;
		return true;
	}

	/** CRC32 over every console's complete save state, in index order. */
	public int hashAll() {
		CRC32 c = new CRC32();
		for (GbaCore core : cores) {
			byte[] st = core.saveState();
			if (st != null) c.update(st, 0, st.length);
		}
		return (int) c.getValue();
	}

	/** Leaving the netplay: close the other consoles and hand the local one back (its state is kept). */
	public GbaCore releaseLocal() {
		for (int i = 0; i < n; i++) if (i != local) cores[i].close();
		return cores[local];
	}

	@Override
	public void close() {
		for (GbaCore c : cores) c.close();
	}
}
