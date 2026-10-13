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
	public static final int PROTOCOL = 1;
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

	public record Open(UUID pakId, String romSha1, String title, byte[] save) implements CustomPacketPayload {
		public static final Type<Open> TYPE = mkType("open");
		public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.ofMember(
			(v, b) -> { b.writeUUID(v.pakId); b.writeUtf(v.romSha1, 64); b.writeUtf(v.title, 64); b.writeByteArray(v.save); },
			b -> new Open(b.readUUID(), b.readUtf(64), b.readUtf(64), b.readByteArray(MAX_SAVE)));
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

	/** Register all payload types (call from the common initializer on both sides). */
	public static void register() {
		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(Hello.TYPE, Hello.CODEC);
		c2s.register(SaveChunk.TYPE, SaveChunk.CODEC);
		c2s.register(Close.TYPE, Close.CODEC);
		c2s.register(Burn.TYPE, Burn.CODEC);
		c2s.register(LinkDataC2S.TYPE, LinkDataC2S.CODEC);
		var s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(Open.TYPE, Open.CODEC);
		s2c.register(LinkState.TYPE, LinkState.CODEC);
		s2c.register(LinkDataS2C.TYPE, LinkDataS2C.CODEC);
	}
}
