package com.drew.gba.client.crt;

import com.drew.gba.client.emu.FrameBuffer;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.crt.StreamSplit;
import com.drew.gba.net.Payloads;
import com.drew.gba.stream.AudioEncoder;
import com.drew.gba.stream.VideoEncoder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encodes the CRT player's picture (every 2nd emulated frame, ~30 fps) and sound and uploads it as crt_stream
 * packets. Runs on its own small thread so neither the emulator nor the render thread waits for the encoder.
 */
public final class CrtStreamer implements Runnable {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink");
	/** 33.3 ms of audio at 32768 Hz; the encoder turns it into 546 mono samples of 16 kHz ADPCM (273 bytes). */
	private static final int AUDIO_FRAMES = 1092;
	private static final long MIN_FRAME_GAP_NANOS = 25_000_000L; // turbo (fast-forward) must not exceed ~30 fps either

	private final UUID crtId;
	private final VideoEncoder video = new VideoEncoder();
	private final AudioEncoder audio = new AudioEncoder();
	private final Thread thread;
	private final Object lock = new Object();
	private final byte[] pending = new byte[GbaNative.WIDTH * GbaNative.HEIGHT * 4];
	private boolean hasFrame;
	private final AtomicBoolean forceKey = new AtomicBoolean(true);
	private final ConcurrentLinkedQueue<short[]> audioQueue = new ConcurrentLinkedQueue<>();
	private volatile boolean stop;
	private int frameCounter, vseq, aseq;
	private long lastOffer;

	// audio accumulation (streamer thread only)
	private final short[] acc = new short[AUDIO_FRAMES * 2 * 4];
	private int accFrames;

	// stats
	public final AtomicLong bytesSent = new AtomicLong();
	public final AtomicLong videoPackets = new AtomicLong();
	private long statStart = System.nanoTime(), statBytes;

	public CrtStreamer(UUID crtId) {
		this.crtId = crtId;
		this.thread = new Thread(this, "gbalink-crt-encoder");
		this.thread.setDaemon(true);
	}

	public void start() { thread.start(); }

	public void close() {
		stop = true;
		synchronized (lock) { lock.notifyAll(); }
	}

	public void requestKeyframe() { forceKey.set(true); }

	/** Emulator thread: called once per emulated frame, right after the frame was published. */
	public void offerFrame(FrameBuffer frames) {
		if ((frameCounter++ & 1) != 0) return;
		long now = System.nanoTime();
		if (now - lastOffer < MIN_FRAME_GAP_NANOS) return;
		lastOffer = now;
		synchronized (lock) {
			frames.copyLetterboxed(pending); // Game Boy pictures are letterboxed into the 240x160 stream canvas (see SPEC.md)
			hasFrame = true;
			lock.notifyAll();
		}
	}

	/** Emulator thread: interleaved stereo samples the session just read from the core. */
	public void offerAudio(short[] pcm, int frames) {
		if (audioQueue.size() > 64) return;
		audioQueue.add(java.util.Arrays.copyOf(pcm, frames * 2));
	}

	@Override
	public void run() {
		byte[] frame = new byte[pending.length];
		while (!stop) {
			boolean got = false;
			synchronized (lock) {
				if (!hasFrame) {
					try { lock.wait(33); } catch (InterruptedException e) { return; }
				}
				if (hasFrame) {
					System.arraycopy(pending, 0, frame, 0, frame.length);
					hasFrame = false;
					got = true;
				}
			}
			try {
				if (got) sendVideo(video.encode(frame, forceKey.getAndSet(false)));
				sendAudio();
			} catch (Throwable t) {
				LOG.warn("CRT stream encode failed", t);
				forceKey.set(true);
			}
			stats();
		}
	}

	private void send(int kind, int seq, byte[] data) {
		java.util.List<byte[]> parts = StreamSplit.split(data, Payloads.MAX_STREAM);
		for (int i = 0; i < parts.size(); i++) {
			try {
				ClientPlayNetworking.send(new Payloads.CrtStreamC2S(crtId, seq, kind, i, parts.size(), parts.get(i)));
			} catch (Throwable ignored) {
				return;
			}
		}
		bytesSent.addAndGet(data.length);
	}

	private void sendVideo(byte[] packet) {
		if (packet == null || packet.length == 0) return; // static picture: nothing to send
		videoPackets.incrementAndGet();
		send(0, vseq++, packet);
	}

	private void sendAudio() {
		short[] chunk;
		while ((chunk = audioQueue.poll()) != null) {
			int frames = chunk.length / 2;
			int off = 0;
			while (off < frames) {
				int n = Math.min(frames - off, AUDIO_FRAMES - accFrames);
				System.arraycopy(chunk, off * 2, acc, accFrames * 2, n * 2);
				accFrames += n;
				off += n;
				if (accFrames == AUDIO_FRAMES) {
					byte[] p = audio.encode(acc, AUDIO_FRAMES);
					if (p != null && p.length > 0) send(1, aseq++, p);
					accFrames = 0;
				}
			}
		}
	}

	private void stats() {
		long now = System.nanoTime();
		if (now - statStart < 5_000_000_000L) return;
		long b = bytesSent.get();
		LOG.info("[CRT-STREAM] uploading {} KB/s ({} video packets so far)", String.format("%.1f", (b - statBytes) / 1024.0 / ((now - statStart) / 1e9)), videoPackets.get());
		statStart = now;
		statBytes = b;
	}
}
