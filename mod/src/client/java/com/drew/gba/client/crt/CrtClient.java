package com.drew.gba.client.crt;

import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.crt.CrtGeometry;
import com.drew.gba.crt.StreamSplit;
import com.drew.gba.net.Payloads;
import com.drew.gba.stream.AudioDecoder;
import com.drew.gba.stream.VideoDecoder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the CRT TV: knows which TVs are live (from crt_open / crt_view), decodes the spectator stream
 * and draws the picture onto the screen of each TV in the world.
 */
public final class CrtClient {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink");
	private static final int W = GbaNative.WIDTH, H = GbaNative.HEIGHT;
	private static final double DRAW_DISTANCE = 80.0;

	/** One TV the client knows about. Render-thread and network-thread (both the client thread) only. */
	private static final class Tv {
		final UUID id;
		double x, y, z;
		float yaw;
		int state;
		String player = "";
		boolean own;
		int volume = 100; // TV volume (the playing player's GBA volume), from crt_volume
		EmulatorSession session; // own TV only
		DynamicTexture tex;
		Identifier texId;
		final byte[] rgba = new byte[W * H * 4];
		boolean dirty, hasFrame, uploadedDark;
		int lastOwnSeq = -1;
		// viewer
		VideoDecoder video = new VideoDecoder();
		AudioDecoder audio = new AudioDecoder();
		final StreamSplit.Reassembler asm = new StreamSplit.Reassembler();
		final short[] pcm = new short[4096];
		long lastKeyReq;
		// stats
		long statStart = System.nanoTime();
		int frames;
		long bytes;
		double fps, kbps;

		Tv(UUID id) { this.id = id; }
	}

	private static final Map<UUID, Tv> tvs = new HashMap<>();
	private static final byte[] scratch = new byte[W * H * 4];

	private static final boolean DEBUG = System.getProperty("gbalink.crtdebug") != null;
	private static int dbg;

	private CrtClient() {}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(CrtClient::render);
	}

	// ------------------------------------------------------------------ state from the network

	/** The server accepted our play request: remember where the TV is and which session drives it. */
	public static void beginOwn(UUID crtId, double x, double y, double z, float yaw, EmulatorSession s) {
		Tv t = tvs.computeIfAbsent(crtId, Tv::new);
		t.x = x; t.y = y; t.z = z; t.yaw = yaw;
		t.own = true;
		t.session = s;
		t.state = Payloads.VIEW_PLAYING;
		t.lastOwnSeq = -1;
		t.hasFrame = false;
	}

	public static void endOwn(UUID crtId) {
		Tv t = tvs.get(crtId);
		if (t != null && t.own) remove(t);
	}

	public static boolean isOwn(UUID crtId) {
		Tv t = tvs.get(crtId);
		return t != null && t.own;
	}

	public static double[] screenCenter(UUID crtId) {
		Tv t = tvs.get(crtId);
		return t == null ? null : CrtGeometry.screenCenter(t.x, t.y, t.z, t.yaw);
	}

	public static void onView(Payloads.CrtView v) {
		Tv t = tvs.get(v.crtId());
		if (t != null && t.own) {
			if (v.state() == Payloads.VIEW_NONE) com.drew.gba.client.ui.CrtScreen.serverEnded(v.crtId());
			return; // our own TV is driven by our session, not by views
		}
		if (v.state() == Payloads.VIEW_NONE) {
			if (t != null) remove(t);
			return;
		}
		if (t == null) {
			t = new Tv(v.crtId());
			tvs.put(t.id, t);
		}
		boolean fresh = t.state != Payloads.VIEW_PLAYING && v.state() == Payloads.VIEW_PLAYING;
		t.x = v.x(); t.y = v.y(); t.z = v.z(); t.yaw = v.yaw();
		t.player = v.playerName();
		t.state = v.state();
		if (fresh) {
			t.video = new VideoDecoder();
			t.audio = new AudioDecoder();
			t.hasFrame = false;
		}
		if (v.state() == Payloads.VIEW_LIMIT) t.hasFrame = false;
		CrtAudio.ensureOpen();
	}

	public static void onVolume(Payloads.CrtVolume v) {
		Tv t = tvs.get(v.crtId());
		if (t != null) t.volume = v.percent();
	}

	public static void onStream(Payloads.CrtStreamS2C p) {
		Tv t = tvs.get(p.crtId());
		if (t == null || t.own || t.state == Payloads.VIEW_NONE) return;
		if (t.state != Payloads.VIEW_PLAYING && p.kind() != 1) return; // over the video cap: sound only
		t.bytes += p.bytes().length;
		try {
			if (p.kind() == 1) {
				playAudio(t, p.bytes());
				return;
			}
			byte[] pkt = t.asm.accept(p.seq(), p.part(), p.parts(), p.bytes());
			if (pkt == null) return;
			if (t.video.decode(pkt, scratch)) {
				System.arraycopy(scratch, 0, t.rgba, 0, scratch.length);
				t.dirty = true;
				t.hasFrame = true;
				t.frames++;
			} else {
				needKeyframe(t);
			}
		} catch (Throwable e) {
			LOG.debug("CRT decode failed", e);
			needKeyframe(t);
		}
		stats(t);
	}

	private static void needKeyframe(Tv t) {
		long now = System.nanoTime();
		if (now - t.lastKeyReq < 1_000_000_000L) return;
		t.lastKeyReq = now;
		try {
			ClientPlayNetworking.send(new Payloads.CrtNeedKeyframe(t.id));
		} catch (Throwable ignored) {
		}
	}

	private static void stats(Tv t) {
		long now = System.nanoTime();
		if (now - t.statStart < 5_000_000_000L) return;
		double s = (now - t.statStart) / 1e9;
		t.fps = t.frames / s;
		t.kbps = t.bytes / 1024.0 / s;
		LOG.info("[CRT-VIEW] {} watching {}: {} fps, {} KB/s", Minecraft.getInstance().getUser().getName(), t.id.toString().substring(0, 8),
			String.format("%.1f", t.fps), String.format("%.1f", t.kbps));
		t.statStart = now;
		t.frames = 0;
		t.bytes = 0;
	}

	/** Dev/test: measured viewer frame rate and bandwidth of a watched TV (updated every 5 s). */
	public static double[] viewerStats(UUID crtId) {
		Tv t = tvs.get(crtId);
		return t == null ? null : new double[] {t.fps, t.kbps};
	}

	public static int viewState(UUID crtId) {
		Tv t = tvs.get(crtId);
		return t == null ? -1 : t.state;
	}

	public static boolean hasFrame(UUID crtId) {
		Tv t = tvs.get(crtId);
		return t != null && t.hasFrame;
	}

	public static UUID anyWatched() {
		for (Tv t : tvs.values()) if (!t.own) return t.id;
		return null;
	}

	private static long audioLogAt;

	/** Dev (-Dgbalink.audiolog): why audio was (not) played, once per second. */
	private static void audioDbg(String what) {
		if (System.getProperty("gbalink.audiolog") == null) return;
		long now = System.currentTimeMillis();
		if (now - audioLogAt < 1000) return;
		audioLogAt = now;
		LOG.info("[CRT-AUDIO] {}", what);
	}

	private static void playAudio(Tv t, byte[] data) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !CrtAudio.available()) { audioDbg("no player or no audio line (available=" + CrtAudio.available() + ")"); return; }
		// only the nearest watched TV is heard
		double[] c = CrtGeometry.screenCenter(t.x, t.y, t.z, t.yaw);
		Vec3 eye = mc.player.getEyePosition();
		double d = Math.sqrt(sq(c[0] - eye.x) + sq(c[1] - eye.y) + sq(c[2] - eye.z));
		for (Tv o : tvs.values()) {
			if (o == t || o.own || o.state == Payloads.VIEW_NONE) continue;
			double[] oc = CrtGeometry.screenCenter(o.x, o.y, o.z, o.yaw);
			if (Math.sqrt(sq(oc[0] - eye.x) + sq(oc[1] - eye.y) + sq(oc[2] - eye.z)) < d) return;
		}
		int n = t.audio.decode(data, t.pcm);
		if (n <= 0) return;
		// pan from the listener's facing: dot of the direction to the TV with the listener's right vector
		double yaw = Math.toRadians(mc.player.getYRot());
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw); // right vector of a player with this yaw
		double dx = c[0] - eye.x, dz = c[2] - eye.z, len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
		double pan = (dx * rx + dz * rz) / len;
		if (len < 1.5) pan *= len / 1.5;
		audioDbg("play n=" + n + " dist=" + String.format("%.1f", d) + " att=" + CrtAudio.attenuation(d) + " tvVolume=" + t.volume + " queued=" + CrtAudio.lastQueued + " skipped=" + CrtAudio.skipped + " written=" + CrtAudio.written + " master=" + mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER));
		CrtAudio.play(t.pcm, n, d, pan, mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER), t.volume);
	}

	private static double sq(double v) { return v * v; }

	public static void reset() {
		for (Tv t : new java.util.ArrayList<>(tvs.values())) remove(t);
	}

	private static void remove(Tv t) {
		tvs.remove(t.id);
		if (t.tex != null) {
			Minecraft.getInstance().getTextureManager().release(t.texId);
			t.tex = null;
		}
	}

	// ------------------------------------------------------------------ drawing

	private static void ensureTexture(Tv t) {
		if (t.tex != null) return;
		t.texId = Identifier.fromNamespaceAndPath("gbalink", "dynamic/crt/" + t.id);
		t.tex = new DynamicTexture("gbalink crt " + t.id, W, H, true);
		Minecraft.getInstance().getTextureManager().register(t.texId, t.tex);
		t.dirty = true;
	}

	/** Copies a frame into the texture with a subtle scanline darkening on every second row. */
	private static void upload(Tv t, byte[] src) {
		NativeImage img = t.tex.getPixels();
		byte[] row = scratchRow;
		for (int y = 0; y < H; y++) {
			System.arraycopy(src, y * W * 4, row, 0, W * 4);
			if ((y & 1) == 1) {
				for (int i = 0; i < W * 4; i += 4) {
					row[i] = (byte) ((row[i] & 255) * 230 / 256);
					row[i + 1] = (byte) ((row[i + 1] & 255) * 230 / 256);
					row[i + 2] = (byte) ((row[i + 2] & 255) * 230 / 256);
				}
			}
			img.getPixelBytes().put(y * W * 4, row, 0, W * 4);
		}
		t.tex.upload();
	}

	private static final byte[] scratchRow = new byte[W * 4];
	private static final byte[] darkFrame = new byte[W * H * 4];

	static {
		for (int i = 0; i < darkFrame.length; i += 4) darkFrame[i + 3] = (byte) 255;
	}

	private static void render(LevelRenderContext ctx) {
		if (tvs.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (DEBUG && (dbg++ % 120) == 0) LOG.info("[CRT-DEBUG] render tvs={} cam={} pose={}", tvs.size(), cam, ctx.poseStack().last().pose());
		for (Tv t : tvs.values()) {
			if (t.state == Payloads.VIEW_NONE) continue;
			double dx = t.x - cam.x, dy = t.y - cam.y, dz = t.z - cam.z;
			if (dx * dx + dy * dy + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) continue;
			ensureTexture(t);
			boolean live = false;
			if (t.own) {
				EmulatorSession s = t.session;
				if (s != null && !s.isFinished()) {
					int seq = s.frames.seq();
					if (seq != t.lastOwnSeq && seq > 0) {
						t.lastOwnSeq = seq;
						s.frames.copyTo(t.rgba);
						t.dirty = true;
						t.hasFrame = true;
					}
				}
			}
			if (t.state == Payloads.VIEW_LIMIT) {
				upload(t, darkFrame);
				t.dirty = false;
				quad(ctx, t, cam);
				limitText(ctx, t, cam, mc);
				continue;
			}
			if (t.dirty) {
				upload(t, t.rgba);
				t.dirty = false;
			} else if (!t.hasFrame && !t.uploadedDark) {
				upload(t, darkFrame);
				t.uploadedDark = true;
			}
			if (t.hasFrame) t.uploadedDark = false;
			quad(ctx, t, cam);
		}
	}

	private static void quad(LevelRenderContext ctx, Tv t, Vec3 cam) {
		double[][] c = CrtGeometry.screenCorners(t.x, t.y, t.z, t.yaw);
		if (DEBUG && dbg % 120 == 1) LOG.info("[CRT-DEBUG] tv=({}, {}, {}) yaw={} cornerTL=({}, {}, {}) cornerBR=({}, {}, {})", t.x, t.y, t.z, t.yaw, c[0][0], c[0][1], c[0][2], c[2][0], c[2][1], c[2][2]);
		float[][] p = new float[4][3];
		for (int i = 0; i < 4; i++) {
			p[i][0] = (float) (c[i][0] - cam.x);
			p[i][1] = (float) (c[i][1] - cam.y);
			p[i][2] = (float) (c[i][2] - cam.z);
		}
		float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
		PoseStack ps = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.text(t.texId), (pose, buf) -> {
			for (int i = 0; i < 4; i++) {
				buf.addVertex(pose, p[i][0], p[i][1], p[i][2]).setColor(-1).setUv(uv[i][0], uv[i][1]).setLight(LightCoordsUtil.FULL_BRIGHT);
			}
		});
	}

	private static void limitText(LevelRenderContext ctx, Tv t, Vec3 cam, Minecraft mc) {
		double[] c = CrtGeometry.screenCenter(t.x, t.y, t.z, t.yaw);
		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		// in front of the screen, upright, facing the TV's front direction
		double yr = Math.toRadians(t.yaw);
		ps.translate(c[0] - cam.x - Math.sin(yr) * 0.01, c[1] - cam.y, c[2] - cam.z + Math.cos(yr) * 0.01);
		ps.rotateDegrees(com.mojang.math.Axis.YP, -t.yaw);
		float s = 0.0115f;
		ps.scale(s, -s, s);
		String msg = "Viewer limit reached";
		Font font = mc.font;
		ps.translate(-font.width(msg) / 2f, -font.lineHeight / 2f, 0f);
		ctx.submitNodeCollector().submitText(ps, 0f, 0f, FormattedCharSequence.forward(msg, net.minecraft.network.chat.Style.EMPTY),
			false, Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, 0xFFFFC080, 0, 0);
		ps.popPose();
	}
}
