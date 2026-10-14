package com.drew.gba.client.spike;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;

import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Both consoles of a 2-player cable in ONE thread, link messages relayed with zero latency in a fixed, deterministic
 * order. This is the core of "dual-simulation netplay"; the only thing that would cross the network is controller input.
 *
 * Tick rule (identical on every machine): input for tick f is script[f - inputDelay]; core 0 (master) runs; whenever it
 * stops in LINK_WAIT its pending messages go to core 1, core 1's immediate replies go back, and only if core 0 is still
 * stuck does core 1 run its own frame; if core 1 did not run yet when core 0 finishes the frame, it runs then.
 */
public final class DualSim implements AutoCloseable {
	public final GbaCore c0, c1;
	private final ArrayDeque<byte[]> to1 = new ArrayDeque<>(), to0 = new ArrayDeque<>();
	public boolean drainAudio; // test hook: drain host audio every tick (the real client does)
	private final short[] pcm = new short[2048 * 2];
	private final int inputDelay;
	private final long seed;
	private final boolean childFirst;
	public long frame;
	public long linkWaits, relayedMsgs, childRuns;
	public boolean useScript = true;
	public int forcedKeys0, forcedKeys1;

	public DualSim(GbaNative n, Path rom, int inputDelay, long seed, boolean childFirst) {
		this.inputDelay = inputDelay;
		this.seed = seed;
		this.childFirst = childFirst;
		c0 = new GbaCore(n);
		c1 = new GbaCore(n);
		if (c0.loadRom(rom) < 0 || c1.loadRom(rom) < 0) throw new IllegalStateException("rom");
		c0.linkConfigure(0, 2);
		c1.linkConfigure(1, 2);
		pump0();
		pump1();
	}

	private void pump0() {
		byte[] m;
		while ((m = c0.linkPollOutgoing()) != null) { to1.add(m); relayedMsgs++; }
	}

	private void pump1() {
		byte[] m;
		while ((m = c1.linkPollOutgoing()) != null) { to0.add(m); relayedMsgs++; }
	}

	private void deliverTo1() {
		byte[] m;
		while ((m = to1.poll()) != null) c1.linkDeliver(0, m);
		pump1();
	}

	private void deliverTo0() {
		byte[] m;
		while ((m = to0.poll()) != null) c0.linkDeliver(1, m);
		pump0();
	}

	private int runChild() {
		deliverTo1();
		int r = c1.runFrame();
		childRuns++;
		if (r == GbaNative.RUN_LINK_WAIT) throw new IllegalStateException("child blocked");
		pump1();
		deliverTo0();
		return r;
	}

	/** Advance both consoles by one frame. Returns false on a core error. */
	public boolean tick() {
		c0.setKeys(useScript ? SpikeSupport.script(0, frame - inputDelay, seed) : forcedKeys0);
		c1.setKeys(useScript ? SpikeSupport.script(1, frame - inputDelay, seed) : forcedKeys1);
		boolean ranChild = false;
		if (childFirst) { runChild(); ranChild = true; }
		int stalls = 0;
		while (true) {
			int r = c0.runFrame();
			pump0();
			if (r == GbaNative.RUN_ERROR) return false;
			if (r == GbaNative.RUN_FRAME_DONE) break;
			linkWaits++;
			boolean newMessage = !to1.isEmpty(); // a fresh START (vs. still waiting for a reply that needs child time)
			deliverTo1();
			deliverTo0();
			if (!newMessage) {
				if (++stalls > 64) throw new IllegalStateException("master stuck in LINK_WAIT");
				runChild();
				ranChild = true;
			}
		}
		if (!ranChild) runChild();
		if (drainAudio) {
			while (c0.readAudio(pcm, 2048) > 0) { }
			while (c1.readAudio(pcm, 2048) > 0) { }
		}
		settle();
		frame++;
		return true;
	}

	/** Deliver everything in flight so both cores are in a snapshot-able state (no transfer pending, queues empty). */
	private void settle() {
		for (int i = 0; i < 8 && (!to1.isEmpty() || !to0.isEmpty()); i++) {
			deliverTo1();
			deliverTo0();
		}
	}

	/** Both consoles' save states; only valid between ticks. */
	public byte[][] snapshot() {
		if (!to0.isEmpty() || !to1.isEmpty()) throw new IllegalStateException("messages in flight");
		return new byte[][] {c0.saveState(), c1.saveState()};
	}

	/** Load a snapshot into both cores (link transfers in flight are not part of a state; none exist at tick boundaries). */
	public boolean restore(byte[][] s, long atFrame) {
		to0.clear();
		to1.clear();
		boolean ok = c0.loadState(s[0]) && c1.loadState(s[1]);
		frame = atFrame;
		pump0();
		pump1();
		return ok;
	}

	@Override
	public void close() {
		c0.close();
		c1.close();
	}
}
