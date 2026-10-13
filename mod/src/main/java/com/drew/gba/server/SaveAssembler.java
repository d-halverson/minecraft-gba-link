package com.drew.gba.server;

import java.util.Arrays;

/** Reassembles a save file uploaded in sequential chunks. One instance per (player, pak). */
public final class SaveAssembler {
	public static final int MAX_TOTAL = 512 * 1024;
	public static final int MAX_CHUNK = 30000;

	private byte[] buffer;
	private int received;

	/**
	 * Feed one chunk. Offsets must arrive in order; offset 0 (re)starts the upload.
	 * @return the complete save when the last chunk arrives, otherwise null
	 * @throws IllegalArgumentException if the chunk is malformed (the upload is discarded)
	 */
	public byte[] accept(int offset, int total, byte[] bytes) {
		if (total < 0 || total > MAX_TOTAL) throw reset("save too large");
		if (bytes.length > MAX_CHUNK) throw reset("chunk too large");
		if (offset == 0) {
			buffer = new byte[total];
			received = 0;
		}
		if (buffer == null || buffer.length != total) throw reset("unexpected chunk");
		if (offset != received) throw reset("out-of-order chunk");
		if ((long) offset + bytes.length > total) throw reset("chunk overflows save");
		System.arraycopy(bytes, 0, buffer, received, bytes.length);
		received += bytes.length;
		if (received == total) {
			byte[] done = Arrays.copyOf(buffer, total);
			buffer = null;
			received = 0;
			return done;
		}
		return null;
	}

	private IllegalArgumentException reset(String why) {
		buffer = null;
		received = 0;
		return new IllegalArgumentException(why);
	}
}
