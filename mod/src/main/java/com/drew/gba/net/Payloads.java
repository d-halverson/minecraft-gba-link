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
	public static final int PROTOCOL = 3;
	public static final int MAX_STREAM = 30000;
	public static final int MAX_SAVE = 512 * 1024;
	public static final int MAX_CHUNK = 30000;
	public static final int MAX_LINK = 64;

	private Payloads() {}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> mkType(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("gbalink", path));
	}

	// ---- C2S ----

	public record Hello(int protocol) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = mkType("hello");
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.ofMember(
			(v, b) -> b.writeVarInt(v.protocol), b -> new Hello(b.readVarInt()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record SaveChunk(UUID pakId, int offset, int total, byte[] bytes) implements CustomPacketPayload {
		public static final Type<SaveChunk> TYPE = mkType("save_chunk");
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveChunk> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.pakId); b.writeVarInt(v.offset); b.writeVarInt(v.total); b.writeByteArray(v.bytes); },
			b -> new SaveChunk(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_CHUNK)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Initial save for the pak the player just burned (protocol 2). Same chunking as SaveChunk, no pak id needed. */
	public record ImportChunk(int offset, int total, byte[] bytes) implements CustomPacketPayload {
		public static final Type<ImportChunk> TYPE = mkType("import_chunk");
		public static final StreamCodec<RegistryFriendlyByteBuf, ImportChunk> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeVarInt(v.offset); b.writeVarInt(v.total); b.writeByteArray(v.bytes); },
			b -> new ImportChunk(b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_CHUNK)));
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
	public record LinkState(int playerIndex, int playerCount, List<String> names) implements CustomPacketPayload {
		public static final Type<LinkState> TYPE = mkType("link_state");
		public static final StreamCodec<RegistryFriendlyByteBuf, LinkState> CODEC = StreamCodec.ofMember(
			(v, b) -> {
				b.writeVarInt(v.playerIndex); b.writeVarInt(v.playerCount);
				b.writeVarInt(v.names.size());
				for (String n : v.names) b.writeUtf(n, 64);
			},
			b -> {
				int idx = b.readVarInt(), cnt = b.readVarInt(), n = b.readVarInt();
				if (n < 0 || n > 8) throw new IllegalArgumentException("bad name count");
				List<String> names = new ArrayList<>(n);
				for (int i = 0; i < n; i++) names.add(b.readUtf(64));
				return new LinkState(idx, cnt, names);
			});
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
	}
}
