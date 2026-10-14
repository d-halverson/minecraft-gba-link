package com.drew.gba.client.emu;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Link cable loop logic for one core, free of Minecraft classes so it can be tested headless.
 * Everything except {@link #offer} and the status getters must run on the emulator thread.
 */
public final class LinkPump {
	private final Consumer<byte[]> sender;
	private final LongSupplier nanos;
	private final long timeoutNanos;
	private final Runnable onTimeout;
	private final LinkedBlockingQueue<Object[]> incoming = new LinkedBlockingQueue<>(4096);
	private GbaCore core;
	private final LinkStats stats;

	private volatile int index, count;
	private volatile List<String> names;
	private volatile boolean waiting, timedOut;

	public LinkPump(Consumer<byte[]> sender, LongSupplier nanos, long timeoutNanos, Runnable onTimeout,
			int index, int count, List<String> names) {
		this.sender = sender;
		this.nanos = nanos;
		this.timeoutNanos = timeoutNanos;
		this.onTimeout = onTimeout;
		this.index = index;
		this.count = count;
		this.names = names;
		this.stats = new LinkStats(nanos.getAsLong());
	}

	public LinkStats stats() { return stats; }

	public void bind(GbaCore core) { this.core = core; }

	public int index() { return index; }
	public int count() { return count; }
	public List<String> names() { return names; }
	public boolean waiting() { return waiting; }
	public boolean timedOut() { return timedOut; }

	/** Thread-safe: a message from another player. Dropped if the queue is full. */
	public void offer(int from, byte[] bytes) {
		incoming.offer(new Object[] {from, bytes});
	}

	/** (Re)configure the cable; also re-plugs after a timeout unplug. */
	public void apply(int idx, int cnt, List<String> nms) {
		index = idx;
		count = cnt;
		names = nms;
		timedOut = false;
		stats.clear(nanos.getAsLong());
		core.linkConfigure(idx, cnt);
		if (cnt < 2) incoming.clear();
	}

	/**
	 * Pacing wait that doubles as a link listener: blocks on the incoming queue for at most {@code nanos} and, as
	 * soon as a partner message arrives, delivers it and sends any message the core produced in response, so a
	 * child's reply does not wait for the next frame boundary. Returns true if something was delivered.
	 * Emulator thread only.
	 */
	public boolean awaitAndDeliver(long nanos) {
		if (count < 2 || nanos <= 0) return false;
		try {
			Object[] m = incoming.poll(nanos, TimeUnit.NANOSECONDS);
			if (m == null) return false;
			deliver(m);
			deliverIncoming();
			pumpOutgoing();
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** True if a partner message is waiting. */
	public boolean hasIncoming() { return !incoming.isEmpty(); }

	/** Deliver queued partner messages; returns how many. */
	public int deliverIncoming() {
		Object[] m;
		int guard = 0;
		while ((m = incoming.poll()) != null && guard < 512) { deliver(m); guard++; }
		return guard;
	}

	private void deliver(Object[] m) {
		if (count < 2) return;
		stats.onIncoming((byte[]) m[1]);
		core.linkDeliver((Integer) m[0], (byte[]) m[1]);
	}

	private void pumpOutgoing() {
		byte[] m;
		int guard = 0;
		while ((m = core.linkPollOutgoing()) != null && guard++ < 256) {
			stats.onOutgoing(m, nanos.getAsLong());
			sender.accept(m);
		}
	}

	/**
	 * Run the core to the end of one frame, servicing the cable. {@code service} runs while blocked
	 * (drain pending commands such as {@link #apply}). Returns GbaNative.RUN_FRAME_DONE, RUN_ERROR,
	 * or RUN_LINK_WAIT if {@code stop} fired mid-transfer.
	 */
	public int runFrame(BooleanSupplier stop, Runnable service) {
		long waitStart = 0;
		while (!stop.getAsBoolean()) {
			int r = core.runFrame();
			pumpOutgoing();
			if (r == GbaNative.RUN_FRAME_DONE) {
				waiting = false;
				stats.recordFrame();
				return r;
			}
			if (r == GbaNative.RUN_ERROR) return r;
			// GBA_RUN_LINK_WAIT: block (briefly) for partner data, then continue the same frame
			if (waitStart == 0) waitStart = nanos.getAsLong();
			waiting = true;
			boolean heard = false;
			long blockedFrom = nanos.getAsLong();
			try {
				Object[] m = incoming.poll(2, TimeUnit.MILLISECONDS);
				if (m != null) { deliver(m); heard = true; }
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return GbaNative.RUN_LINK_WAIT;
			}
			stats.recordBlocked(nanos.getAsLong() - blockedFrom);
			if (deliverIncoming() > 0 || heard) waitStart = nanos.getAsLong(); // partner is alive: restart the timeout
			service.run();
			if (nanos.getAsLong() - waitStart > timeoutNanos) {
				core.linkConfigure(0, 0); // unplug locally; unblocks the pending transfer
				count = 0;
				timedOut = true;
				waiting = false;
				waitStart = 0;
				onTimeout.run();
			}
		}
		return GbaNative.RUN_LINK_WAIT;
	}
}
