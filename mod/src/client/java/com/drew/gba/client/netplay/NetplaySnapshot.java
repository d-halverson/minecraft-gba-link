package com.drew.gba.client.netplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Deflated snapshot blob: the consoles' save states and battery saves plus the netplay frame they were taken before and the
 * input delay in effect. Session start: one console (the sender's). Resync: all consoles, from player 0.
 */
public record NetplaySnapshot(int frame, int delay, int[] players, byte[][] states, byte[][] saves) {
	private static final int MAGIC = 0x4E505331; // NPS1
	public static final int MAX_STATE = 2 * 1024 * 1024, MAX_RAW = 12 * 1024 * 1024;

	public byte[] encode() {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
			Deflater df = new Deflater(1);
			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bos, df, 16 * 1024))) {
				out.writeInt(MAGIC);
				out.writeInt(frame);
				out.writeInt(delay);
				out.writeByte(players.length);
				for (int i = 0; i < players.length; i++) {
					out.writeByte(players[i]);
					out.writeInt(states[i].length);
					out.write(states[i]);
					byte[] sv = saves[i] == null ? new byte[0] : saves[i];
					out.writeInt(sv.length);
					out.write(sv);
				}
			} finally {
				df.end();
			}
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Returns null if the blob is malformed. */
	public static NetplaySnapshot decode(byte[] blob) {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(blob)))) {
			if (in.readInt() != MAGIC) return null;
			int frame = in.readInt(), delay = in.readInt(), n = in.readUnsignedByte();
			if (n < 1 || n > 4) return null;
			int[] players = new int[n];
			byte[][] states = new byte[n][], saves = new byte[n][];
			long total = 0;
			for (int i = 0; i < n; i++) {
				players[i] = in.readUnsignedByte();
				int sl = in.readInt();
				if (sl <= 0 || sl > MAX_STATE || (total += sl) > MAX_RAW) return null;
				states[i] = new byte[sl];
				in.readFully(states[i]);
				int vl = in.readInt();
				if (vl < 0 || vl > 1024 * 1024 || (total += vl) > MAX_RAW) return null;
				saves[i] = new byte[vl];
				in.readFully(saves[i]);
			}
			return new NetplaySnapshot(frame, delay, players, states, saves);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
