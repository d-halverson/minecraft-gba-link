package com.drew.gba.client.emu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;


/**
 * One emulator core. Not thread safe: every method must be called from the single emulator thread
 * (the one that created it), as required by the native ABI.
 */
public final class GbaCore implements AutoCloseable {
	private static final int AUDIO_CHUNK_FRAMES = 2048;

	private final GbaNative n;
	private final Arena arena = Arena.ofConfined();
	private MemorySegment core;
	private final MemorySegment audioBuf = arena.allocate(AUDIO_CHUNK_FRAMES * 4L, 4);
	private final MemorySegment linkBuf = arena.allocate(GbaNative.LINK_MSG_MAX, 1);
	private MemorySegment scratch = arena.allocate(1024, 1);

	public GbaCore(GbaNative n) {
		this.n = n;
		try {
			core = (MemorySegment) n.create.invokeExact();
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
		if (core.equals(MemorySegment.NULL)) throw new IllegalStateException("gba_create failed");
	}

	private static RuntimeException wrap(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	private MemorySegment scratch(long size) {
		if (scratch.byteSize() < size) scratch = arena.allocate(size, 8);
		return scratch;
	}

	public int loadRom(Path rom) {
		try {
			return (int) n.loadRom.invokeExact(core, arena.allocateFrom(rom.toAbsolutePath().toString()));
		} catch (Throwable t) { throw wrap(t); }
	}

	/** Returns {title, code}. */
	public String[] romInfo() {
		try {
			MemorySegment t = arena.allocate(16), c = arena.allocate(8);
			int r = (int) n.romInfo.invokeExact(core, t, c);
			if (r < 0) return new String[] {"", ""};
			return new String[] {t.getString(0, StandardCharsets.UTF_8), c.getString(0, StandardCharsets.UTF_8)};
		} catch (Throwable t) { throw wrap(t); }
	}

	public void reset() {
		try { n.reset.invokeExact(core); } catch (Throwable t) { throw wrap(t); }
	}

	public void setKeys(int mask) {
		try { n.setKeys.invokeExact(core, mask); } catch (Throwable t) { throw wrap(t); }
	}

	public int runFrame() {
		try { return (int) n.runFrame.invokeExact(core); } catch (Throwable t) { throw wrap(t); }
	}

	/** {@link GbaNative#PLATFORM_GBA} or {@link GbaNative#PLATFORM_GB} (Game Boy and Game Boy Color); always GBA for a library older than API 4. */
	public int platform() {
		if (n.platform == null) return GbaNative.PLATFORM_GBA;
		try { return (int) n.platform.invokeExact(core); } catch (Throwable t) { throw wrap(t); }
	}

	public boolean isGameBoy() { return platform() == GbaNative.PLATFORM_GB; }

	/** Width of the picture the core produces: 240 (GBA) or 160 (Game Boy). Valid for the loaded ROM. */
	public int frameWidth() {
		if (n.frameWidth == null) return GbaNative.WIDTH;
		try { return (int) n.frameWidth.invokeExact(core); } catch (Throwable t) { throw wrap(t); }
	}

	/** Height of the picture the core produces: 160 (GBA) or 144 (Game Boy). */
	public int frameHeight() {
		if (n.frameHeight == null) return GbaNative.HEIGHT;
		try { return (int) n.frameHeight.invokeExact(core); } catch (Throwable t) { throw wrap(t); }
	}

	/** Copy the RGBA framebuffer ({@link #frameWidth()} x {@link #frameHeight()} x 4 bytes) into dst, which must be at least that long. */
	public void copyFramebuffer(byte[] dst) {
		try {
			long bytes = (long) frameWidth() * frameHeight() * 4;
			MemorySegment fb = ((MemorySegment) n.framebuffer.invokeExact(core)).reinterpret(bytes);
			MemorySegment.copy(fb, ValueLayout.JAVA_BYTE, 0, dst, 0, (int) bytes);
		} catch (Throwable t) { throw wrap(t); }
	}

	/** Drain up to maxFrames stereo frames into out as interleaved shorts; returns frames written. */
	public int readAudio(short[] out, int maxFrames) {
		try {
			int frames = Math.min(maxFrames, AUDIO_CHUNK_FRAMES);
			long got = (long) n.readAudio.invokeExact(core, audioBuf, (long) frames);
			int g = (int) Math.max(0, Math.min(got, frames));
			MemorySegment.copy(audioBuf, ValueLayout.JAVA_SHORT, 0, out, 0, g * 2);
			return g;
		} catch (Throwable t) { throw wrap(t); }
	}

	public int loadSave(byte[] data) {
		try {
			MemorySegment s = scratch(data.length);
			MemorySegment.copy(data, 0, s, ValueLayout.JAVA_BYTE, 0, data.length);
			return (int) n.loadSave.invokeExact(core, s, (long) data.length);
		} catch (Throwable t) { throw wrap(t); }
	}

	public boolean saveDirty() {
		try { return (int) n.saveDirty.invokeExact(core) != 0; } catch (Throwable t) { throw wrap(t); }
	}

	/** Current battery save, or null if the game has none. */
	public byte[] getSave() {
		try {
			long size = (long) n.saveSize.invokeExact(core);
			if (size <= 0 || size > 64L * 1024 * 1024) return null;
			MemorySegment s = scratch(size);
			long w = (long) n.getSave.invokeExact(core, s, size);
			byte[] out = new byte[(int) Math.min(w, size)];
			MemorySegment.copy(s, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
			return out;
		} catch (Throwable t) { throw wrap(t); }
	}

	public byte[] saveState() {
		try {
			long size = (long) n.stateSize.invokeExact(core);
			if (size <= 0) return null;
			MemorySegment s = scratch(size);
			long w = (long) n.saveState.invokeExact(core, s, size);
			if (w <= 0) return null;
			byte[] out = new byte[(int) w];
			MemorySegment.copy(s, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
			return out;
		} catch (Throwable t) { throw wrap(t); }
	}

	public boolean loadState(byte[] data) {
		try {
			MemorySegment s = scratch(data.length);
			MemorySegment.copy(data, 0, s, ValueLayout.JAVA_BYTE, 0, data.length);
			return (int) n.loadState.invokeExact(core, s, (long) data.length) == 0;
		} catch (Throwable t) { throw wrap(t); }
	}

	/** Virtual RTC (API 2): mode GbaNative.RTC_VIRTUAL reads {@code epochSeconds} now and advances with emulated time. False if unsupported. */
	public boolean setRtc(long epochSeconds, int mode) {
		if (n.setRtc == null) return mode == GbaNative.RTC_WALL;
		try { return (int) n.setRtc.invokeExact(core, epochSeconds, mode) == 0; } catch (Throwable t) { throw wrap(t); }
	}

	/** Native handle for gba_group_add (package use: GbaGroup). */
	public GbaNative natives() { return n; }

	MemorySegment handle() { return core; }

	public void linkConfigure(int index, int count) {
		try { n.linkConfigure.invokeExact(core, index, count); } catch (Throwable t) { throw wrap(t); }
	}

	/** Pop one outgoing link message, or null. */
	public byte[] linkPollOutgoing() {
		try {
			long len = (long) n.linkPollOutgoing.invokeExact(core, linkBuf, (long) GbaNative.LINK_MSG_MAX);
			if (len <= 0) return null;
			byte[] out = new byte[(int) Math.min(len, GbaNative.LINK_MSG_MAX)];
			MemorySegment.copy(linkBuf, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
			return out;
		} catch (Throwable t) { throw wrap(t); }
	}

	public void linkDeliver(int from, byte[] data) {
		try {
			int len = Math.min(data.length, GbaNative.LINK_MSG_MAX);
			MemorySegment.copy(data, 0, linkBuf, ValueLayout.JAVA_BYTE, 0, len);
			n.linkDeliver.invokeExact(core, from, linkBuf, (long) len);
		} catch (Throwable t) { throw wrap(t); }
	}

	@Override
	public void close() {
		try {
			if (core != null && !core.equals(MemorySegment.NULL)) n.destroy.invokeExact(core);
		} catch (Throwable t) {
			// ignore on shutdown
		} finally {
			core = MemorySegment.NULL;
			arena.close();
		}
	}
}
