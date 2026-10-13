package com.drew.gba.stream;

/**
 * Decodes {@link AudioEncoder} packets to 16 kHz mono int16. Stateless between packets (each packet carries its
 * predictor and step index), so lost packets only cause a gap. Never throws on bad input (returns 0).
 */
public final class AudioDecoder {
	/** @return number of samples written to mono16kOut */
	public int decode(byte[] packet, short[] mono16kOut) {
		try {
			if (packet == null || mono16kOut == null || packet.length < AudioEncoder.HEADER) return 0;
			if ((packet[0] & 255) != AudioEncoder.MAGIC) return 0;
			int idx = packet[1] & 255;
			if (idx > 88) return 0;
			int pred = (short) ((packet[2] & 255) | (packet[3] << 8));
			int n = (packet[4] & 255) | ((packet[5] & 255) << 8);
			if (packet.length != AudioEncoder.HEADER + (n + 1) / 2) return 0;
			n = Math.min(n, mono16kOut.length);
			for (int i = 0; i < n; i++) {
				int b = packet[AudioEncoder.HEADER + (i >> 1)];
				int code = (i & 1) == 0 ? b & 15 : (b >> 4) & 15;
				int step = ImaAdpcm.STEP[idx];
				int vp = step >> 3;
				if ((code & 4) != 0) vp += step;
				if ((code & 2) != 0) vp += step >> 1;
				if ((code & 1) != 0) vp += step >> 2;
				pred += (code & 8) != 0 ? -vp : vp;
				pred = pred > 32767 ? 32767 : pred < -32768 ? -32768 : pred;
				idx += ImaAdpcm.INDEX[code & 7];
				idx = idx < 0 ? 0 : idx > 88 ? 88 : idx;
				mono16kOut[i] = (short) pred;
			}
			return n;
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
