package com.drew.gba.crt;

import java.util.ArrayList;
import java.util.List;

/** Splitting of oversized stream packets and the viewer-side reassembly. */
public final class StreamSplit {
	private StreamSplit() {}

	/** Splits {@code data} into pieces of at most {@code max} bytes (always at least one piece). */
	public static List<byte[]> split(byte[] data, int max) {
		List<byte[]> out = new ArrayList<>();
		int off = 0;
		do {
			int len = Math.min(max, data.length - off);
			out.add(java.util.Arrays.copyOfRange(data, off, off + len));
			off += len;
		} while (off < data.length);
		return out;
	}

	/** Collects the parts of one sequence number. A newer sequence number abandons an incomplete older one. */
	public static final class Reassembler {
		private int seq = -1;
		private byte[][] parts;
		private int have;
		public int dropped;

		/** @return the complete packet when the last part arrives, else null */
		public byte[] accept(int seq, int part, int total, byte[] bytes) {
			if (total <= 1) {
				if (this.parts != null) dropped++;
				this.parts = null;
				return bytes;
			}
			if (total > 64 || part < 0 || part >= total) return null;
			if (parts == null || seq != this.seq || parts.length != total) {
				if (parts != null && have < parts.length) dropped++;
				this.seq = seq;
				parts = new byte[total][];
				have = 0;
			}
			if (parts[part] == null) {
				parts[part] = bytes;
				have++;
			}
			if (have < total) return null;
			int n = 0;
			for (byte[] b : parts) n += b.length;
			byte[] out = new byte[n];
			int o = 0;
			for (byte[] b : parts) {
				System.arraycopy(b, 0, out, o, b.length);
				o += b.length;
			}
			parts = null;
			return out;
		}
	}
}
