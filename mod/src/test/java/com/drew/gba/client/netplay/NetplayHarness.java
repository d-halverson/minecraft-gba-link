package com.drew.gba.client.netplay;

import com.drew.gba.client.emu.GbaCore;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.spike.SpikeSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In-process stand-in for N Minecraft clients and the server relay: each client has its own emulator thread running a real
 * {@link NetplayController}; the fake server relays inputs, hashes and snapshots with a one-way delay (plus jitter), in
 * order per receiver like TCP does. Test sources only.
 */
final class NetplayHarness implements AutoCloseable {
	final GbaNative natives;
	final Path rom;
	final long oneWayMs, jitterMs;
	final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "fake-net"); t.setDaemon(true); return t; });
	final List<Client> clients = new ArrayList<>();
	private final Random rnd = new Random(1234);
	private final Map<String, Long> lastDue = new ConcurrentHashMap<>();
	private long nextSession = 1;
	/** hash log: client id -> (gen<<32|frame) -> hash */
	final Map<Integer, Map<Long, Integer>> hashLog = new ConcurrentHashMap<>();

	NetplayHarness(GbaNative natives, Path rom, long oneWayMs, long jitterMs) {
		this.natives = natives;
		this.rom = rom;
		this.oneWayMs = oneWayMs;
		this.jitterMs = jitterMs;
	}

	Client addClient() {
		Client c = new Client(clients.size());
		clients.add(c);
		hashLog.put(c.id, new ConcurrentHashMap<>());
		return c;
	}

	/** Deliver {@code r} on the receiver's link after the simulated latency, keeping per-link order. */
	void deliver(int from, int to, Runnable r) {
		synchronized (lastDue) {
			long now = System.nanoTime();
			long d = oneWayMs + (jitterMs > 0 ? rnd.nextInt((int) jitterMs * 2 + 1) - jitterMs : 0);
			long due = Math.max(now + Math.max(0, d) * 1_000_000L, lastDue.getOrDefault(from + ">" + to, 0L));
			lastDue.put(from + ">" + to, due);
			sched.schedule(r, Math.max(0, due - now), TimeUnit.NANOSECONDS);
		}
	}

	/** Start (or restart) a netplay session for these clients (index = position in the list). */
	void startSession(List<Client> members) {
		long sid = nextSession++;
		long epoch = 1_760_000_000L + sid;
		List<String> names = members.stream().map(c -> "T" + c.id).toList();
		for (int i = 0; i < members.size(); i++) {
			Client c = members.get(i);
			c.members = members;
			NetplayEngine.Params p = new NetplayEngine.Params(sid, i, members.size(), names, epoch, 0, 3);
			c.post(() -> c.ctl.start(p));
		}
	}

	void endSession(List<Client> members) {
		for (Client c : members) c.post(() -> c.ctl.end());
	}

	final class Client implements Runnable, NetplayEngine.Transport, NetplayController.Events {
		final int id;
		final Thread thread;
		final ConcurrentLinkedQueue<Runnable> cmds = new ConcurrentLinkedQueue<>();
		volatile List<Client> members = List.of();
		volatile boolean stop;
		volatile Throwable failure;
		volatile long standaloneFrames, netplayFrames;
		volatile int keysHeld;
		final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
		NetplayController ctl;
		GbaCore core;
		int warmup = 120;

		Client(int id) {
			this.id = id;
			thread = new Thread(this, "client-" + id);
			thread.setDaemon(true);
		}

		void post(Runnable r) { cmds.add(r); }

		<T> T call(Function<NetplayController, T> f) throws Exception {
			java.util.concurrent.CompletableFuture<T> fut = new java.util.concurrent.CompletableFuture<>();
			post(() -> { try { fut.complete(f.apply(ctl)); } catch (Throwable t) { fut.completeExceptionally(t); } });
			return fut.get(10, TimeUnit.SECONDS);
		}

		void start() { thread.start(); }

		private int myIndex() { return members.indexOf(this); }

		private void toOthers(long session, Consumer<Client> c) {
			List<Client> m = members;
			for (Client o : m) if (o != this) deliver(id, o.id, () -> c.accept(o));
		}

		// ---- Transport
		@Override public void input(long session, int first, short[] recs, int sendMs, int[] echo) {
			int from = myIndex();
			toOthers(session, o -> o.ctl.onInput(session, from, first, recs, sendMs, echo));
		}

		@Override public void hash(long session, int gen, int frame, int hash) {
			int from = myIndex();
			hashLog.get(id).put(((long) gen << 32) | frame, hash);
			toOthers(session, o -> o.ctl.onHash(session, from, gen, frame, hash));
		}

		@Override public void snapshot(long session, int gen, int frame, byte[] blob) {
			int from = myIndex();
			int off = 0;
			do {
				int len = Math.min(30000, blob.length - off);
				byte[] chunk = java.util.Arrays.copyOfRange(blob, off, off + len);
				int o2 = off;
				toOthers(session, o -> o.ctl.onSnapshot(session, from, gen, frame, o2, blob.length, chunk));
				off += len;
			} while (off < blob.length);
		}

		@Override public void restart(long session) { events.add("restart requested"); }

		// ---- Events
		@Override public void toast(String m) { events.add("toast: " + m); }
		@Override public void log(String m) { events.add(m); }
		@Override public void left(String r) { events.add("left: " + r); }

		@Override
		public void run() {
			try {
				core = new GbaCore(natives);
				if (core.loadRom(rom) < 0) throw new IllegalStateException("rom");
				ctl = new NetplayController(natives, rom, core, this, System::nanoTime, this);
				long next = System.nanoTime();
				while (!stop) {
					Runnable r;
					while ((r = cmds.poll()) != null) r.run();
					boolean wasRunning = ctl.running();
					if (ctl.iterate(() -> keysHeld, c -> netplayFrames++, () -> stop)) continue;
					// standalone
					GbaCore cc = ctl.core();
					cc.setKeys(keysHeld);
					cc.runFrame();
					while (cc.readAudio(new short[4096], 2048) > 0) { }
					standaloneFrames++;
					next += NetplayController.FRAME_NANOS;
					long w = next - System.nanoTime();
					if (w > 0) Thread.sleep(w / 1_000_000L, (int) (w % 1_000_000L));
					else if (-w > 100_000_000L) next = System.nanoTime();
				}
				ctl.shutdown();
			} catch (Throwable t) {
				t.printStackTrace();
				failure = t;
			} finally {
				if (ctl != null) { try { ctl.core().close(); } catch (Throwable ignored) { } }
			}
		}

		SpikeSupport.Counters counters(int console) throws Exception {
			return call(c -> SpikeSupport.Counters.of(c.engine().sim().core(console).saveState()));
		}
	}

	@Override
	public void close() throws InterruptedException {
		for (Client c : clients) c.stop = true;
		for (Client c : clients) c.thread.join(5000);
		sched.shutdownNow();
	}
}
