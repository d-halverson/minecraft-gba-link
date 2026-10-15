package com.drew.gba.client.ui;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Remembers a volume when the first GBA / CRT session screen opens and puts it back when the last one closes. The MC
 * volume wheel changes Minecraft's master volume live (never written to options.txt while playing, so a crash leaves
 * the original on disk); the restore sets the original again and saves the options. No Minecraft classes (unit-tested).
 * Sub-screens (import picker, confirmations, options panel) are not "closing": the screens only call {@link #end} from
 * their real {@code removed()}.
 */
public final class VolumeRestore {
	private final DoubleSupplier get;
	private final DoubleConsumer set;
	private final Runnable save;
	private final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
	private double saved;

	public VolumeRestore(DoubleSupplier get, DoubleConsumer set, Runnable save) {
		this.get = get;
		this.set = set;
		this.save = save;
	}

	/** A session screen opened. The first owner takes the snapshot; later owners (overlap) share it. */
	public synchronized void begin(Object owner) {
		if (owners.isEmpty()) saved = get.getAsDouble();
		owners.add(owner);
	}

	/** A session screen closed. The last owner out restores. Returns true if it restored (the volume had changed). */
	public synchronized boolean end(Object owner) {
		if (!owners.remove(owner) || !owners.isEmpty()) return false;
		return restoreNow();
	}

	/** Restore regardless of owners (client stopping). */
	public synchronized boolean endAll() {
		if (owners.isEmpty()) return false;
		owners.clear();
		return restoreNow();
	}

	public synchronized boolean active() { return !owners.isEmpty(); }

	private boolean restoreNow() {
		if (Math.abs(get.getAsDouble() - saved) < 1e-9) return false;
		set.accept(saved);
		save.run();
		return true;
	}
}
