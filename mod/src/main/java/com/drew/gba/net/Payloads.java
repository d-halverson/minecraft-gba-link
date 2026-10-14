package com.drew.gba.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All gbalink custom payloads (see gba/SPEC.md). */
public final class Payloads {
	public static final int PROTOCOL = 4;
	public static final int MAX_STREAM = 30000;
	public static final int MAX_SAVE = 512 * 1024;
	public static final int MAX_CHUNK = 30000;
	public static final int MAX_LINK = 64;

	private Payloads() {}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> mkType(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("gbalink", path));
	}

	// ---- C2S ----

	/** {@code caps} (appended in 0.4.0, 0 if absent): bit 0 = this client can run input-sync netplay (native API 2). */
	public record Hello(int protocol, int caps) implements CustomPacketPayload {
		public static final int CAP_NETPLAY = 1;
		/** 0.4.2: netplay simulated with mGBA lockstep groups (native API 3); not compatible with the 0.4.0/0.4.1 simulation (bit 0 only). */
		public static final int CAP_NETPLAY_LOCKSTEP = 2;
		public static final Type<Hello> TYPE = mkType("hello");
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarInt(v.protocol); b.writeVarInt(v.caps); },
			b -> new Hello(b.readVarInt(), b.isReadable() ? b.readVarInt() : 0));
		public Hello(int protocol) { this(protocol, 0); }
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record SaveChunk(UUID pakId, int offset, int total, byte[] bytes) implements CustomPacketPayload {
		public static final Type<SaveChunk> TYPE = mkType("save_chunk");
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveChunk> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.pakId); b.writeVarInt(v.offset); b.writeVarInt(v.total); b.writeByteArray(v.bytes); },
			b -> new SaveChunk(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_CHUNK)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Replace the save of the pak this player currently has open with an imported one (protocol 4; replaces the
	 * burn-time import of protocol 2/3). Same chunking as SaveChunk; answered with a SaveAck.
	 */
	public record ImportChunk(UUID pakId, int offset, int total, byte[] bytes) implements CustomPacketPayload {
		public static final Type<ImportChunk> TYPE = mkType("import_save");
		public static final StreamCodec<RegistryFriendlyByteBuf, ImportChunk> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.pakId); b.writeVarInt(v.offset); b.writeVarInt(v.total); b.writeByteArray(v.bytes); },
			b -> new ImportChunk(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_CHUNK)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Close(UUID pakId) implements CustomPacketPayload {
		public static final Type<Close> TYPE = mkType("close");
		public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeUUID(v.pakId), b -> new Close(b.readUUID()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Burn(String romSha1, String title, String gameCode) implements CustomPacketPayload {
		public static final Type<Burn> TYPE = mkType("burn");
		public static final StreamCodec<RegistryFriendlyByteBuf, Burn> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUtf(v.romSha1, 64); b.writeUtf(v.title, 64); b.writeUtf(v.gameCode, 16); },
			b -> new Burn(b.readUtf(64), b.readUtf(64), b.readUtf(16)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record LinkDataC2S(byte[] bytes) implements CustomPacketPayload {
		public static final Type<LinkDataC2S> TYPE = mkType("link_data");
		public static final StreamCodec<RegistryFriendlyByteBuf, LinkDataC2S> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeByteArray(v.bytes), b -> new LinkDataC2S(b.readByteArray(MAX_LINK)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// ---- S2C ----

	/**
	 * revision = SHA-256 hex of {@code save} (protocol 2). The field is appended last and decoded tolerantly, so a
	 * protocol 2 client reading a protocol 1 server's Open gets an empty revision instead of a decode error.
	 */
	public record Open(UUID pakId, String romSha1, String title, byte[] save, String revision) implements CustomPacketPayload {
		public static final Type<Open> TYPE = mkType("open");
		public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.pakId); b.writeUtf(v.romSha1, 64); b.writeUtf(v.title, 64); b.writeByteArray(v.save); b.writeUtf(v.revision, 64); },
			b -> new Open(b.readUUID(), b.readUtf(64), b.readUtf(64), b.readByteArray(MAX_SAVE), b.isReadable() ? b.readUtf(64) : ""));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server's answer to Hello: its protocol number (old servers never send it). */
	public record Welcome(int protocol) implements CustomPacketPayload {
		public static final Type<Welcome> TYPE = mkType("welcome");
		public static final StreamCodec<RegistryFriendlyByteBuf, Welcome> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeVarInt(v.protocol), b -> new Welcome(b.readVarInt()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Sent after a completed save upload (or import). {@code revision} is the server's save revision now, i.e. the
	 * SHA-256 of what is stored; {@code accepted} is false if the upload was refused (then revision is unchanged).
	 */
	public record SaveAck(UUID pakId, String revision, boolean accepted, String message) implements CustomPacketPayload {
		public static final Type<SaveAck> TYPE = mkType("save_ack");
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveAck> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.pakId); b.writeUtf(v.revision, 64); b.writeBoolean(v.accepted); b.writeUtf(v.message, 128); },
			b -> new SaveAck(b.readUUID(), b.readUtf(64), b.readBoolean(), b.readUtf(128)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** count &lt; 2 means unplugged. */
	public record LinkState(int playerIndex, int playerCount, List<String> names, int mode) implements CustomPacketPayload {
		public static final int MODE_CLASSIC = 0, MODE_NETPLAY = 1;
		public LinkState(int playerIndex, int playerCount, List<String> names) { this(playerIndex, playerCount, names, MODE_CLASSIC); }
		public static final Type<LinkState> TYPE = mkType("link_state");
		public static final StreamCodec<RegistryFriendlyByteBuf, LinkState> CODEC = StreamCodec.ofMember(
			(v, b) -> {
				b.writeVarInt(v.playerIndex); b.writeVarInt(v.playerCount);
				b.writeVarInt(v.names.size());
				for (String n : v.names) b.writeUtf(n, 64);
				b.writeVarInt(v.mode);
			},
			b -> {
				int idx = b.readVarInt(), cnt = b.readVarInt(), n = b.readVarInt();
				if (n < 0 || n > 8) throw new IllegalArgumentException("bad name count");
				List<String> names = new ArrayList<>(n);
				for (int i = 0; i < n; i++) names.add(b.readUtf(64));
				return new LinkState(idx, cnt, names, b.isReadable() ? b.readVarInt() : MODE_CLASSIC);
			});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * S2C (optional, additive; only sent to clients that registered it): who is on this player's link cable, even when no
	 * GBA is open. {@code myIndex} is -1 and {@code members} empty when not cabled. {@code open[i]} is true when member i
	 * has a GBA (or CRT) running.
	 */
	public record CableState(int myIndex, List<String> members, List<Boolean> open) implements CustomPacketPayload {
		public static final Type<CableState> TYPE = mkType("cable_state");
		public static final StreamCodec<RegistryFriendlyByteBuf, CableState> CODEC = StreamCodec.ofMember(
			(v, b) -> {
				b.writeVarInt(v.myIndex);
				b.writeVarInt(v.members.size());
				for (int i = 0; i < v.members.size(); i++) { b.writeUtf(v.members.get(i), 64); b.writeBoolean(v.open.get(i)); }
			},
			b -> {
				int idx = b.readVarInt(), n = b.readVarInt();
				if (n < 0 || n > 8) throw new IllegalArgumentException("bad member count");
				List<String> names = new ArrayList<>(n);
				List<Boolean> open = new ArrayList<>(n);
				for (int i = 0; i < n; i++) { names.add(b.readUtf(64)); open.add(b.readBoolean()); }
				return new CableState(idx, names, open);
			});
		public static final CableState NONE = new CableState(-1, List.of(), List.of());
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** C2S: "take me off the link cable" (same as /gba unlink). Empty. */
	public record Unlink() implements CustomPacketPayload {
		public static final Type<Unlink> TYPE = mkType("unlink");
		public static final StreamCodec<RegistryFriendlyByteBuf, Unlink> CODEC = StreamCodec.ofMember((v, b) -> { }, b -> new Unlink());
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * The TV volume (0-100): the GBA-volume setting of the player at the CRT. C2S from that player (on start and on every
	 * change); S2C relayed to everyone who can hear the TV, so the player and the audience hear the same level.
	 * Optional and additive: clients without it just hear the TV at full level.
	 */
	public record CrtVolume(UUID crtId, int percent) implements CustomPacketPayload {
		public static final Type<CrtVolume> TYPE = mkType("crt_volume");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtVolume> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.crtId); b.writeVarInt(v.percent); }, b -> new CrtVolume(b.readUUID(), b.readVarInt()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S2C (optional): blocks within which a running CRT TV is audible (server setting crt_audio_distance). */
	public record CrtAudioRange(int blocks) implements CustomPacketPayload {
		public static final Type<CrtAudioRange> TYPE = mkType("crt_audio_range");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtAudioRange> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeVarInt(v.blocks), b -> new CrtAudioRange(b.readVarInt()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record LinkDataS2C(int fromIndex, byte[] bytes) implements CustomPacketPayload {
		public static final Type<LinkDataS2C> TYPE = mkType("link_data");
		public static final StreamCodec<RegistryFriendlyByteBuf, LinkDataS2C> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarInt(v.fromIndex); b.writeByteArray(v.bytes); },
			b -> new LinkDataS2C(b.readVarInt(), b.readByteArray(MAX_LINK)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// ---- CRT TV (protocol 3) ----

	/** C2S: one packet of the streamer's video (kind 0) or audio (kind 1); big frames are split into parts. */
	public record CrtStreamC2S(UUID crtId, int seq, int kind, int part, int parts, byte[] bytes) implements CustomPacketPayload {
		public static final Type<CrtStreamC2S> TYPE = mkType("crt_stream");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtStreamC2S> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.crtId); b.writeVarInt(v.seq); b.writeVarInt(v.kind); b.writeVarInt(v.part); b.writeVarInt(v.parts); b.writeByteArray(v.bytes); },
			b -> new CrtStreamC2S(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_STREAM)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** C2S: the streamer stops playing the CRT. */
	public record CrtClose(UUID crtId) implements CustomPacketPayload {
		public static final Type<CrtClose> TYPE = mkType("crt_close");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtClose> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeUUID(v.crtId), b -> new CrtClose(b.readUUID()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** C2S: a viewer could not decode; the server forwards a (rate-limited) keyframe request to the streamer. */
	public record CrtNeedKeyframe(UUID crtId) implements CustomPacketPayload {
		public static final Type<CrtNeedKeyframe> TYPE = mkType("crt_need_keyframe");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtNeedKeyframe> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeUUID(v.crtId), b -> new CrtNeedKeyframe(b.readUUID()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S2C: like Open, plus where the TV is (x,y,z = bottom centre of the TV, yaw = direction its front faces). */
	public record CrtOpen(UUID crtId, UUID pakId, String romSha1, String title, byte[] save, String revision,
			double x, double y, double z, float yaw) implements CustomPacketPayload {
		public static final Type<CrtOpen> TYPE = mkType("crt_open");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtOpen> CODEC = StreamCodec.ofMember(
			(v, b) -> {
				b.writeUUID(v.crtId); b.writeUUID(v.pakId); b.writeUtf(v.romSha1, 64); b.writeUtf(v.title, 64);
				b.writeByteArray(v.save); b.writeUtf(v.revision, 64);
				b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); b.writeFloat(v.yaw);
			},
			b -> new CrtOpen(b.readUUID(), b.readUUID(), b.readUtf(64), b.readUtf(64), b.readByteArray(MAX_SAVE), b.readUtf(64),
				b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S2C: relayed stream packet for viewers. */
	public record CrtStreamS2C(UUID crtId, int seq, int kind, int part, int parts, byte[] bytes) implements CustomPacketPayload {
		public static final Type<CrtStreamS2C> TYPE = mkType("crt_stream");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtStreamS2C> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.crtId); b.writeVarInt(v.seq); b.writeVarInt(v.kind); b.writeVarInt(v.part); b.writeVarInt(v.parts); b.writeByteArray(v.bytes); },
			b -> new CrtStreamS2C(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_STREAM)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S2C: asks the streamer for a keyframe. */
	public record CrtKeyframeRequest(UUID crtId) implements CustomPacketPayload {
		public static final Type<CrtKeyframeRequest> TYPE = mkType("crt_keyframe_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtKeyframeRequest> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeUUID(v.crtId), b -> new CrtKeyframeRequest(b.readUUID()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static final int VIEW_NONE = 0, VIEW_PLAYING = 1, VIEW_LIMIT = 2;

	/** S2C: state of a TV for this viewer (0 none/idle, 1 playing, 2 viewer limit reached). Also ends the player's own play. */
	public record CrtView(UUID crtId, int state, String playerName, double x, double y, double z, float yaw) implements CustomPacketPayload {
		public static final Type<CrtView> TYPE = mkType("crt_view");
		public static final StreamCodec<RegistryFriendlyByteBuf, CrtView> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.crtId); b.writeVarInt(v.state); b.writeUtf(v.playerName, 64); b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); b.writeFloat(v.yaw); },
			b -> new CrtView(b.readUUID(), b.readVarInt(), b.readUtf(64), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}


	// ---- Netplay link (protocol 4, 0.4.0) ----

	public static final int MAX_NETPLAY_RECS = 16, MAX_NETPLAY_ECHO = 12, MAX_NETPLAY_SNAPSHOT = 4 * 1024 * 1024;

	private static void writeInput(RegistryFriendlyByteBuf b, long session, int first, short[] recs, int sendMs, int[] echo) {
		b.writeVarLong(session); b.writeVarInt(first); b.writeVarInt(recs.length);
		for (short r : recs) b.writeShort(r);
		b.writeVarInt(sendMs); b.writeVarInt(echo.length);
		for (int e : echo) b.writeVarInt(e);
	}

	private static short[] readRecs(RegistryFriendlyByteBuf b) {
		int n = b.readVarInt();
		if (n < 0 || n > MAX_NETPLAY_RECS) throw new IllegalArgumentException("bad record count");
		short[] r = new short[n];
		for (int i = 0; i < n; i++) r[i] = b.readShort();
		return r;
	}

	private static int[] readEcho(RegistryFriendlyByteBuf b) {
		int n = b.readVarInt();
		if (n < 0 || n > MAX_NETPLAY_ECHO) throw new IllegalArgumentException("bad echo count");
		int[] e = new int[n];
		for (int i = 0; i < n; i++) e[i] = b.readVarInt();
		return e;
	}

	/** S2C: a netplay session begins (or restarts) for this player. */
	public record NetplayStart(long sessionId, int index, int count, List<String> names, long epoch, int tzOffset, int delay) implements CustomPacketPayload {
		public static final Type<NetplayStart> TYPE = mkType("netplay_start");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplayStart> CODEC = StreamCodec.ofMember(
			(v, b) -> {
				b.writeVarLong(v.sessionId); b.writeVarInt(v.index); b.writeVarInt(v.count);
				b.writeVarInt(v.names.size());
				for (String n : v.names) b.writeUtf(n, 64);
				b.writeLong(v.epoch); b.writeInt(v.tzOffset); b.writeVarInt(v.delay);
			},
			b -> {
				long sid = b.readVarLong(); int idx = b.readVarInt(), cnt = b.readVarInt(), n = b.readVarInt();
				if (n < 0 || n > 8) throw new IllegalArgumentException("bad name count");
				List<String> names = new ArrayList<>(n);
				for (int i = 0; i < n; i++) names.add(b.readUtf(64));
				return new NetplayStart(sid, idx, cnt, names, b.readLong(), b.readInt(), b.readVarInt());
			});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * C2S: controller records for frames firstFrame.. (one short each: keys in bits 0-9, delay proposal in bits 10-13),
	 * the sender's clock (ms) and echoes (player, theirSendMs, holdMs triples, flattened) for round trip measurement.
	 */
	public record NetplayInputC2S(long sessionId, int firstFrame, short[] recs, int sendMs, int[] echo) implements CustomPacketPayload {
		public static final Type<NetplayInputC2S> TYPE = mkType("netplay_input");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplayInputC2S> CODEC = StreamCodec.ofMember(
			(v, b) -> writeInput(b, v.sessionId, v.firstFrame, v.recs, v.sendMs, v.echo),
			b -> new NetplayInputC2S(b.readVarLong(), b.readVarInt(), readRecs(b), b.readVarInt(), readEcho(b)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record NetplayInputS2C(long sessionId, int from, int firstFrame, short[] recs, int sendMs, int[] echo) implements CustomPacketPayload {
		public static final Type<NetplayInputS2C> TYPE = mkType("netplay_input");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplayInputS2C> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarInt(v.from); writeInput(b, v.sessionId, v.firstFrame, v.recs, v.sendMs, v.echo); },
			b -> { int from = b.readVarInt(); return new NetplayInputS2C(b.readVarLong(), from, b.readVarInt(), readRecs(b), b.readVarInt(), readEcho(b)); });
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** C2S: hash of all consoles after netplay frame {@code frame} (every 60th frame), tagged with the resync generation. */
	public record NetplayHashC2S(long sessionId, int gen, int frame, int hash) implements CustomPacketPayload {
		public static final Type<NetplayHashC2S> TYPE = mkType("netplay_hash");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplayHashC2S> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarLong(v.sessionId); b.writeVarInt(v.gen); b.writeVarInt(v.frame); b.writeInt(v.hash); },
			b -> new NetplayHashC2S(b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readInt()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record NetplayHashS2C(long sessionId, int from, int gen, int frame, int hash) implements CustomPacketPayload {
		public static final Type<NetplayHashS2C> TYPE = mkType("netplay_hash");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplayHashS2C> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarInt(v.from); b.writeVarLong(v.sessionId); b.writeVarInt(v.gen); b.writeVarInt(v.frame); b.writeInt(v.hash); },
			b -> { int from = b.readVarInt(); return new NetplayHashS2C(b.readVarLong(), from, b.readVarInt(), b.readVarInt(), b.readInt()); });
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * C2S: one chunk (at most 30000 bytes) of a deflated snapshot blob. gen 0 = a player's own console at session start,
	 * gen &gt; 0 = player 0's resync snapshot of all consoles taken before netplay frame {@code frame}.
	 */
	public record NetplaySnapshotC2S(long sessionId, int gen, int frame, int offset, int total, byte[] bytes) implements CustomPacketPayload {
		public static final Type<NetplaySnapshotC2S> TYPE = mkType("netplay_snapshot");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplaySnapshotC2S> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarLong(v.sessionId); b.writeVarInt(v.gen); b.writeVarInt(v.frame); b.writeVarInt(v.offset); b.writeVarInt(v.total); b.writeByteArray(v.bytes); },
			b -> new NetplaySnapshotC2S(b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_CHUNK)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record NetplaySnapshotS2C(long sessionId, int from, int gen, int frame, int offset, int total, byte[] bytes) implements CustomPacketPayload {
		public static final Type<NetplaySnapshotS2C> TYPE = mkType("netplay_snapshot");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplaySnapshotS2C> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarInt(v.from); b.writeVarLong(v.sessionId); b.writeVarInt(v.gen); b.writeVarInt(v.frame); b.writeVarInt(v.offset); b.writeVarInt(v.total); b.writeByteArray(v.bytes); },
			b -> { int from = b.readVarInt(); return new NetplaySnapshotS2C(b.readVarLong(), from, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_CHUNK)); });
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** C2S: this client cannot continue the session (failure it could not recover from); the server restarts it for everyone. */
	public record NetplayRestartC2S(long sessionId) implements CustomPacketPayload {
		public static final Type<NetplayRestartC2S> TYPE = mkType("netplay_restart");
		public static final StreamCodec<RegistryFriendlyByteBuf, NetplayRestartC2S> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeVarLong(v.sessionId), b -> new NetplayRestartC2S(b.readVarLong()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Register all payload types (call from the common initializer on both sides). */
	public static void register() {
		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(Hello.TYPE, Hello.CODEC);
		c2s.register(SaveChunk.TYPE, SaveChunk.CODEC);
		c2s.register(ImportChunk.TYPE, ImportChunk.CODEC);
		c2s.register(Close.TYPE, Close.CODEC);
		c2s.register(Burn.TYPE, Burn.CODEC);
		c2s.register(LinkDataC2S.TYPE, LinkDataC2S.CODEC);
		c2s.register(CrtStreamC2S.TYPE, CrtStreamC2S.CODEC);
		c2s.register(CrtClose.TYPE, CrtClose.CODEC);
		c2s.register(CrtNeedKeyframe.TYPE, CrtNeedKeyframe.CODEC);
		c2s.register(NetplayInputC2S.TYPE, NetplayInputC2S.CODEC);
		c2s.register(NetplayHashC2S.TYPE, NetplayHashC2S.CODEC);
		c2s.register(NetplaySnapshotC2S.TYPE, NetplaySnapshotC2S.CODEC);
		c2s.register(NetplayRestartC2S.TYPE, NetplayRestartC2S.CODEC);
		c2s.register(Unlink.TYPE, Unlink.CODEC);
		c2s.register(CrtVolume.TYPE, CrtVolume.CODEC);
		var s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(Open.TYPE, Open.CODEC);
		s2c.register(Welcome.TYPE, Welcome.CODEC);
		s2c.register(SaveAck.TYPE, SaveAck.CODEC);
		s2c.register(LinkState.TYPE, LinkState.CODEC);
		s2c.register(LinkDataS2C.TYPE, LinkDataS2C.CODEC);
		s2c.register(CrtOpen.TYPE, CrtOpen.CODEC);
		s2c.register(CrtStreamS2C.TYPE, CrtStreamS2C.CODEC);
		s2c.register(CrtKeyframeRequest.TYPE, CrtKeyframeRequest.CODEC);
		s2c.register(CrtView.TYPE, CrtView.CODEC);
		s2c.register(NetplayStart.TYPE, NetplayStart.CODEC);
		s2c.register(NetplayInputS2C.TYPE, NetplayInputS2C.CODEC);
		s2c.register(NetplayHashS2C.TYPE, NetplayHashS2C.CODEC);
		s2c.register(NetplaySnapshotS2C.TYPE, NetplaySnapshotS2C.CODEC);
		s2c.register(CableState.TYPE, CableState.CODEC);
		s2c.register(CrtVolume.TYPE, CrtVolume.CODEC);
		s2c.register(CrtAudioRange.TYPE, CrtAudioRange.CODEC);
	}
}
