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
public record SuspendFile(String revision, String romSha1, boolean confirmed, byte[] state) {
	private static final int MAGIC = 0x47425353; // "GBSS"
	private static final int VERSION = 1;

	public byte[] encode() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream(state.length + 200);
		DataOutputStream o = new DataOutputStream(bo);
		o.writeInt(MAGIC);
		o.writeByte(VERSION);
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
			if (i.readInt() != MAGIC || i.readByte() != VERSION) return null;
			String rev = i.readUTF(), rom = i.readUTF();
			boolean conf = i.readBoolean();
			int len = i.readInt();
			if (len <= 0 || len > 16 * 1024 * 1024 || len != i.available()) return null;
			byte[] st = new byte[len];
			i.readFully(st);
			return new SuspendFile(rev, rom, conf, st);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
