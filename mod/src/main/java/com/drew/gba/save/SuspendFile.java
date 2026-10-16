package com.drew.gba.save;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * On-disk suspend state: a core save state plus what it is based on.
 * {@code revision} is the server save revision (SHA-256) that was current when the game was suspended, i.e. the
 * revision AFTER the suspend flush upload; {@code confirmed} says whether the server acknowledged it.
 */
public record SuspendFile(String revision, String romSha1, boolean confirmed, byte[] state, String system) {
	private static final int MAGIC = 0x47425353; // "GBSS"
	/** 1: GBA only (no system field). 2 (0.5.0): adds the system ("gba", "gb" or "gbc") after the version byte. */
	private static final int VERSION = 2;

	public SuspendFile {
		system = system == null || system.isEmpty() ? "gba" : system;
	}

	/** A GBA suspend state (what every one was before 0.5.0). */
	public SuspendFile(String revision, String romSha1, boolean confirmed, byte[] state) {
		this(revision, romSha1, confirmed, state, "gba");
	}

	public byte[] encode() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream(state.length + 200);
		DataOutputStream o = new DataOutputStream(bo);
		o.writeInt(MAGIC);
		o.writeByte(VERSION);
		o.writeUTF(system);
		o.writeUTF(revision);
		o.writeUTF(romSha1);
		o.writeBoolean(confirmed);
		o.writeInt(state.length);
		o.write(state);
		return bo.toByteArray();
	}

	/** @return the decoded file, or null if the bytes are not a valid suspend file */
	public static SuspendFile decode(byte[] data) {
		try {
			DataInputStream i = new DataInputStream(new ByteArrayInputStream(data));
			if (i.readInt() != MAGIC) return null;
			int version = i.readByte();
			if (version != 1 && version != VERSION) return null;
			String system = version >= 2 ? i.readUTF() : "gba";
			String rev = i.readUTF(), rom = i.readUTF();
			boolean conf = i.readBoolean();
			int len = i.readInt();
			if (len <= 0 || len > 16 * 1024 * 1024 || len != i.available()) return null;
			byte[] st = new byte[len];
			i.readFully(st);
			return new SuspendFile(rev, rom, conf, st, system);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
