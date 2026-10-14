package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaGroup;
import com.drew.gba.client.emu.GbaNative;

import java.util.zip.CRC32;

/**
 * All consoles of a link (2 to 4) in ONE thread, linked with mGBA's own multiplayer lockstep (native API 3, gba_group_*)
 * and stepped by the native library in a fixed order. Every machine runs the same simulation from the same inputs, so
 * only controller input has to cross the network (see NetplayEngine).
 *
 * <p>Since 0.4.2 the emulated cable is mGBA's {@code GBASIOLockstep} (the code behind mGBA's multiplayer windows), not the
 * hand-written link driver of 0.4.0/0.4.1: that one never passed Pokemon Emerald's cable club ("communication error").
 * Any change to the simulation must be versioned with the netplay capability (Hello.CAP_NETPLAY_LOCKSTEP).
 */
public final class NetplaySim implements AutoCloseable {
	private final GbaCore[] cores;
	private final GbaGroup group;
	private final int n, local;
	private final short[] pcm = new short[2048 * 2];
	public long ticks;

	public NetplaySim(GbaCore[] cores, int local) {
		this.cores = cores;
		this.n = cores.length;
		this.local = local;
		this.group = new GbaGroup(cores[0].natives(), cores);
	}

	public int players() { return n; }
	public int localIndex() { return local; }
	public GbaCore core(int i) { return cores[i]; }
	public GbaCore localCore() { return cores[local]; }

	/** Advance every console by one frame with the given keys (one mask per player). Returns false on a core error. */
	public boolean tick(int[] keys) {
		for (int i = 0; i < n; i++) cores[i].setKeys(keys[i]);
		if (group.runFrame() != GbaNative.RUN_FRAME_DONE) return false;
		for (int j = 0; j < n; j++) if (j != local) while (cores[j].readAudio(pcm, 2048) > 0) { } // other consoles are silent
		ticks++;
		return true;
	}

	/** CRC32 over every console's complete save state (including its lockstep state), in index order. */
	public int hashAll() {
		CRC32 c = new CRC32();
		for (GbaCore core : cores) {
			byte[] st = core.saveState();
			if (st != null) c.update(st, 0, st.length);
		}
		return (int) c.getValue();
	}

	/** Leaving the netplay: dissolve the link, close the other consoles and hand the local one back (its state is kept). */
	public GbaCore releaseLocal() {
		group.close();
		for (int i = 0; i < n; i++) if (i != local) cores[i].close();
		return cores[local];
	}

	@Override
	public void close() {
		group.close();
		for (GbaCore c : cores) c.close();
	}
}
