package com.drew.gba.client.emu;

import java.lang.foreign.MemorySegment;

/**
 * Cores linked with mGBA's own lockstep (native API 3, gba_group_*): all consoles run in one thread, one frame per
 * {@link #runFrame()}, deterministically. Create it after every core has its ROM, save, state and virtual RTC; closing the
 * group gives the cores their classic link driver back (idle). Same thread rules as {@link GbaCore}.
 */
public final class GbaGroup implements AutoCloseable {
	private final GbaNative n;
	private MemorySegment group;

	public GbaGroup(GbaNative n, GbaCore... cores) {
		this.n = n;
		if (n.groupCreate == null) throw new IllegalStateException("native library without lockstep groups");
		try {
			group = (MemorySegment) n.groupCreate.invokeExact(cores.length);
			if (group.equals(MemorySegment.NULL)) throw new IllegalStateException("gba_group_create failed");
			for (GbaCore c : cores) {
				int r = (int) n.groupAdd.invokeExact(group, c.handle());
				if (r != 0) throw new IllegalStateException("gba_group_add failed: " + r);
			}
		} catch (RuntimeException e) {
			close();
			throw e;
		} catch (Throwable t) {
			close();
			throw new IllegalStateException(t);
		}
	}

	/** Advances every core by one video frame. Returns GbaNative.RUN_FRAME_DONE or RUN_ERROR. */
	public int runFrame() {
		try { return (int) n.groupRunFrame.invokeExact(group); } catch (Throwable t) { throw new IllegalStateException(t); }
	}

	@Override
	public void close() {
		try {
			if (group != null && !group.equals(MemorySegment.NULL)) n.groupDestroy.invokeExact(group);
		} catch (Throwable t) {
			// ignore on shutdown
		} finally {
			group = MemorySegment.NULL;
		}
	}
}
