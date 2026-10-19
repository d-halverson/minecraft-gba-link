package com.drew.gba.server;

import com.drew.gba.GbaItems;
import com.drew.gba.crt.CrtConfig;
import com.drew.gba.crt.CrtGeometry;
import com.drew.gba.crt.CrtViewers;
import com.drew.gba.net.Payloads;
import com.drew.gba.save.SaveRules;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CRT TV: placing (item_display + interaction entities, no registered blocks), play control and the spectator
 * stream relay. State lives on the server thread; the Netty-thread fast path only reads an immutable snapshot.
 */
public final class CrtServer {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink");
	public static final String TAG = "gbalink_crt";
	private static final double PLAY_RANGE = 12.0;
	private static final long KEYFRAME_GAP_NANOS = 500_000_000L;

	/** Settings from config/gbalink.properties (loaded at server start). */
	public static volatile CrtConfig config = CrtConfig.defaults();

	private final GbaServer gba;
	private final MinecraftServer server;
	private final Map<UUID, Live> live = new HashMap<>();
	private final Map<UUID, UUID> byPlayer = new HashMap<>();
	private int tickCounter;

	CrtServer(GbaServer gba, MinecraftServer server) {
		this.gba = gba;
		this.server = server;
	}

	/** One CRT that somebody is playing. */
	private static final class Live {
		final UUID crtId;
		final UUID player;
		final String playerName;
		final ResourceKey<Level> dim;
		final double x, y, z;
		final float yaw;
		Map<UUID, Integer> viewerState = new HashMap<>();
		/** Viewers over the video cap that are still close enough to hear the TV (audio is not capped). */
		java.util.Set<UUID> audioOnly = new java.util.HashSet<>();
		volatile int volume = 100; // TV volume: the playing player's GBA volume
		final TokenBucket bucket = new TokenBucket(256 * 1024, 1024 * 1024);
		final AtomicLong lastKeyframe = new AtomicLong(System.nanoTime() - 1_000_000_000L);
		final AtomicLong bytes = new AtomicLong();
		long lastStatBytes, lastStatNanos = System.nanoTime();

		Live(UUID crtId, ServerPlayer p, ResourceKey<Level> dim, double x, double y, double z, float yaw) {
			this.crtId = crtId;
			this.player = p.getUUID();
			this.playerName = p.getName().getString();
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
		}
	}

	/** Immutable routing for the Netty thread. */
	private record Relay(UUID crtId, ServerGamePacketListenerImpl streamer, List<ServerGamePacketListenerImpl> viewers,
			List<ServerGamePacketListenerImpl> audioOnly, TokenBucket bucket, AtomicLong lastKeyframe, AtomicLong bytes) {}

	private static volatile Map<UUID, Relay> relays = Map.of();

	// ------------------------------------------------------------------ entity state

	/** State stored in the interaction entity's custom data. */
	record State(UUID crtId, PakData pak, float yaw, int part) {}

	private static CompoundTag crtTag(Interaction e) {
		CustomData cd = e.get(DataComponents.CUSTOM_DATA);
		if (cd == null) return null;
		return cd.copyTag().getCompound(GbaItems.ROOT).flatMap(g -> g.getCompound("crt")).orElse(null);
	}

	static State stateOf(Interaction e) {
		CompoundTag t = crtTag(e);
		if (t == null) return null;
		try {
			UUID id = UUID.fromString(t.getStringOr("id", ""));
			return new State(id, GbaItems.pakFromTag(t.getCompound("pak").orElse(null)), t.getFloatOr("yaw", 0f), t.getIntOr("part", 0));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static void writeState(Interaction e, State s) {
		CompoundTag crt = new CompoundTag();
		crt.putString("id", s.crtId().toString());
		crt.putFloat("yaw", s.yaw());
		crt.putInt("part", s.part());
		if (s.pak() != null) crt.put("pak", GbaItems.pakToTag(s.pak()));
		CompoundTag g = new CompoundTag();
		g.put("crt", crt);
		CompoundTag root = new CompoundTag();
		root.put(GbaItems.ROOT, g);
		e.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	public static boolean isCrt(Entity e) {
		return e instanceof Interaction i && stateOf(i) != null;
	}

	// ------------------------------------------------------------------ placing

	InteractionResult place(ServerPlayer p, BlockHitResult hit) {
		if (!(p.level() instanceof ServerLevel level)) return InteractionResult.PASS;
		BlockPos target = hit.getBlockPos().relative(hit.getDirection());
		float yaw = CrtGeometry.yawFacingPlayer(p.getYRot());
		// the TV is 2 wide: the clicked cell is the viewer's left cell, the second cell is on the viewer's right
		BlockPos second = target.offset(Math.round((float) Math.cos(Math.toRadians(yaw))), 0, Math.round((float) Math.sin(Math.toRadians(yaw))));
		for (BlockPos c : new BlockPos[] {target, second}) {
			for (int dy = 0; dy < 2; dy++) {
				BlockPos q = c.above(dy);
				if (!level.isInWorldBounds(q) || !level.getBlockState(q).canBeReplaced()) {
					GbaServer.hint(p, "The CRT TV needs a free space 2 wide, 2 high and 1 deep");
					return InteractionResult.SUCCESS;
				}
			}
		}
		AABB area = new AABB(Math.min(target.getX(), second.getX()), target.getY(), Math.min(target.getZ(), second.getZ()),
			Math.max(target.getX(), second.getX()) + 1, target.getY() + 2.0, Math.max(target.getZ(), second.getZ()) + 1);
		List<Entity> blockers = level.getEntities((Entity) null, area, e -> e instanceof Interaction || (e instanceof ServerPlayer sp && !sp.isSpectator()));
		if (!blockers.isEmpty()) {
			GbaServer.hint(p, "Something is in the way of the CRT TV");
			return InteractionResult.SUCCESS;
		}
		ItemStack held = p.getItemInHand(InteractionHand.MAIN_HAND);
		PakData pak = GbaItems.insertedPak(held);
		UUID id = UUID.randomUUID();
		double cx = target.getX() + 0.5, cz = target.getZ() + 0.5, by = target.getY();
		String tag = "\"" + TAG + "\",\"" + TAG + "_" + id + "\"";
		StringBuilder pakNbt = new StringBuilder();
		if (pak != null) {
			pakNbt.append(",pak:{pakId:\"").append(pak.pakId()).append("\",sha1:\"").append(pak.sha1()).append("\",title:")
				.append(quote(pak.title())).append(",code:").append(quote(pak.code()))
				.append(pak.gameBoy() ? ",system:" + quote(pak.system()) : "").append("}");
		}
		String disp = String.format(java.util.Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {Tags:[%s],Rotation:[%.1ff,0f],item_display:\"fixed\",billboard:\"fixed\",width:4.0f,height:3.0f,"
				+ "item:{id:\"minecraft:echo_shard\",count:1,components:{\"minecraft:item_model\":\"gbalink:crt_placed\"}}}",
			cx, by + 0.5, cz, tag, yaw);
		var src = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(src, disp);
		for (int part = 0; part < 2; part++) {
			BlockPos cell = part == 0 ? target : second;
			String crt = "{id:\"" + id + "\",yaw:" + yaw + "f,part:" + part + (part == 0 ? pakNbt : "") + "}";
			String inter = String.format(java.util.Locale.ROOT,
				"summon minecraft:interaction %.3f %.3f %.3f {Tags:[%s],width:%.2ff,height:%.2ff,response:0b,data:{gbalink:{crt:%s}}}",
				cell.getX() + 0.5, (double) cell.getY(), cell.getZ() + 0.5, tag, CrtGeometry.HIT_WIDTH, CrtGeometry.HIT_HEIGHT, crt);
			server.getCommands().performPrefixedCommand(src, inter);
		}
		if (!p.isCreative()) held.shrink(1);
		LOG.info("{} placed CRT {} at {} {} {} yaw {}", p.getName().getString(), id, target.getX(), target.getY(), target.getZ(), yaw);
		return InteractionResult.SUCCESS;
	}

	/** The part that carries the state (pak): the clicked part itself, or its partner. */
	private static Interaction primaryOf(Interaction e, State st) {
		if (st.part() == 0) return e;
		String tag = TAG + "_" + st.crtId();
		for (Interaction i : e.level().getEntitiesOfClass(Interaction.class, e.getBoundingBox().inflate(3.0), x -> x.entityTags().contains(tag))) {
			State s2 = stateOf(i);
			if (s2 != null && s2.part() == 0) return i;
		}
		return null;
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	// ------------------------------------------------------------------ interacting

	InteractionResult onUse(ServerPlayer p, InteractionHand hand, Interaction clicked) {
		State clickedState = stateOf(clicked);
		if (clickedState == null) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
		Interaction e = primaryOf(clicked, clickedState);
		State st = e == null ? null : stateOf(e);
		if (st == null) return InteractionResult.SUCCESS;
		ItemStack off = p.getItemInHand(InteractionHand.OFF_HAND);
		if (p.isShiftKeyDown()) {
			if (live.containsKey(st.crtId())) { GbaServer.hint(p, "Someone is playing"); return InteractionResult.SUCCESS; }
			if (st.pak() == null) {
				PakData pak = GbaItems.pakOf(off);
				if (pak == null) { GbaServer.hint(p, "Hold a Game Pak in your off hand and sneak-click to insert it"); return InteractionResult.SUCCESS; }
				writeState(e, new State(st.crtId(), pak, st.yaw(), 0));
				p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
				GbaServer.hint(p, "Inserted " + pak.title());
			} else {
				if (!off.isEmpty()) { GbaServer.hint(p, "Empty your off hand to eject the pak"); return InteractionResult.SUCCESS; }
				p.setItemInHand(InteractionHand.OFF_HAND, GbaItems.createPak(st.pak()));
				writeState(e, new State(st.crtId(), null, st.yaw(), 0));
				GbaServer.hint(p, "Ejected " + st.pak().title());
			}
			return InteractionResult.SUCCESS;
		}
		begin(p, e, st);
		return InteractionResult.SUCCESS;
	}

	private void begin(ServerPlayer p, Interaction e, State st) {
		if (st.pak() == null) { GbaServer.hint(p, "No Game Pak inserted. Sneak-click with a pak in your off hand."); return; }
		if (!ServerPlayNetworking.canSend(p, Payloads.CrtOpen.TYPE)) { GbaServer.hint(p, "Install the GBA Link client mod to play"); return; }
		Integer proto = GbaServer.protocolOf(p.getUUID());
		if (proto == null || proto != Payloads.PROTOCOL) {
			GbaServer.hint(p, proto != null && proto > Payloads.PROTOCOL
				? "The server's GBA Link mod is older than yours - ask the admin to update it"
				: "Your GBA Link mod is out of date - update the mod to play");
			return;
		}
		if (st.pak().gameBoy() && !GbaServer.canGameBoy(p.getUUID())) {
			GbaServer.hint(p, "Your GBA Link mod can't play Game Boy games - update the mod to play");
			return;
		}
		Live existing = live.get(st.crtId());
		if (existing != null && !existing.player.equals(p.getUUID())) { GbaServer.hint(p, "Someone is playing"); return; }
		if (e.distanceToSqr(p) > PLAY_RANGE * PLAY_RANGE) { GbaServer.hint(p, "Too far away"); return; }
		PakData pak = st.pak();
		if (existing != null) { // 0.5.3: the same player came back to the TV whose screen they had left: the client re-attaches, nothing restarts
			ServerPlayNetworking.send(p, new Payloads.CrtOpen(existing.crtId, pak.pakId(), pak.sha1(), pak.title(), new byte[0], "",
				existing.x, existing.y, existing.z, existing.yaw));
			return;
		}
		endForPlayer(p.getUUID());
		byte[] save = gba.store().read(pak.pakId());
		gba.openSession(p, pak.pakId(), pak.sha1(), pak.system());
		Live l = new Live(st.crtId(), p, p.level().dimension(), e.getX(), e.getY(), e.getZ(), st.yaw());
		live.put(l.crtId, l);
		byPlayer.put(p.getUUID(), l.crtId);
		ServerPlayNetworking.send(p, new Payloads.CrtOpen(l.crtId, pak.pakId(), pak.sha1(), pak.title(), save, SaveRules.revision(save),
			l.x, l.y, l.z, l.yaw));
		gba.refreshLinkOf(p.getUUID());
		refresh(l);
		LOG.info("{} started CRT {} ({})", l.playerName, l.crtId, pak.title());
	}

	boolean onPunch(ServerPlayer p, Interaction clicked) {
		State st0 = stateOf(clicked);
		if (st0 == null) return false;
		Interaction prim = primaryOf(clicked, st0);
		State st = prim == null ? st0 : stateOf(prim);
		Live l = live.get(st0.crtId());
		if (l != null) end(l, true);
		if (!(clicked.level() instanceof ServerLevel level)) return true;
		String tag = TAG + "_" + st0.crtId();
		int removed = 0;
		for (Entity part : level.getEntities((Entity) null, clicked.getBoundingBox().inflate(3.0), x -> x.entityTags().contains(tag))) {
			part.discard();
			removed++;
		}
		if (removed == 0) return true;
		Entity at = prim != null ? prim : clicked;
		ItemEntity drop = new ItemEntity(level, at.getX(), at.getY() + 0.5, at.getZ(), GbaItems.createCrt(st == null ? null : st.pak()));
		drop.setDefaultPickUpDelay();
		level.addFreshEntity(drop);
		LOG.info("{} broke CRT {} ({} parts)", p.getName().getString(), st0.crtId(), removed);
		return true;
	}

	// ------------------------------------------------------------------ lifecycle

	/** The player's CRT play ends (their session closed, they left, or the TV went away). */
	void endForPlayer(UUID player) {
		UUID id = byPlayer.get(player);
		Live l = id == null ? null : live.get(id);
		if (l != null) end(l, false);
	}

	void onCrtClose(ServerPlayer p, UUID crtId) {
		Live l = live.get(crtId);
		if (l != null && l.player.equals(p.getUUID())) end(l, false);
	}

	private void end(Live l, boolean tellStreamer) {
		live.remove(l.crtId);
		byPlayer.remove(l.player);
		for (UUID v : l.viewerState.keySet()) sendView(v, l, Payloads.VIEW_NONE);
		if (tellStreamer) sendView(l.player, l, Payloads.VIEW_NONE);
		l.viewerState = new HashMap<>();
		l.audioOnly = new java.util.HashSet<>();
		rebuildRelays();
		LOG.info("CRT {} stopped", l.crtId);
	}

	private void sendView(UUID to, Live l, int state) {
		ServerPlayer sp = server.getPlayerList().getPlayer(to);
		if (sp != null && ServerPlayNetworking.canSend(sp, Payloads.CrtView.TYPE)) {
			ServerPlayNetworking.send(sp, new Payloads.CrtView(l.crtId, state, l.playerName, l.x, l.y, l.z, l.yaw));
		}
	}

	void onDisconnect(UUID id) {
		endForPlayer(id);
		for (Live l : live.values()) l.viewerState.remove(id);
		rebuildRelays();
	}

	// ------------------------------------------------------------------ viewers

	void tick() {
		tickCounter++;
		if (tickCounter % 20 != 0 || live.isEmpty()) return;
		for (Live l : new ArrayList<>(live.values())) {
			ServerPlayer sp = server.getPlayerList().getPlayer(l.player);
			if (sp == null || sp.level().dimension() != l.dim || sp.distanceToSqr(l.x, l.y + 0.6, l.z) > (PLAY_RANGE + 6) * (PLAY_RANGE + 6)) {
				if (sp != null) GbaServer.hint(sp, "You walked away from the TV");
				end(l, true);
				continue;
			}
			refresh(l);
			long now = System.nanoTime();
			if (now - l.lastStatNanos >= 5_000_000_000L) {
				long b = l.bytes.get();
				double kbps = (b - l.lastStatBytes) / 1024.0 / ((now - l.lastStatNanos) / 1e9);
				LOG.info("[CRT-STAT] {} streamed in: {} KB/s, viewers {}", l.crtId.toString().substring(0, 8), String.format("%.1f", kbps / Math.max(1, 1)), countState(l, 1));
				l.lastStatBytes = b;
				l.lastStatNanos = now;
			}
		}
	}

	private static int countState(Live l, int state) {
		int n = 0;
		for (int s : l.viewerState.values()) if (s == state) n++;
		return n;
	}

	private void refresh(Live l) {
		CrtConfig cfg = config;
		List<CrtViewers.Candidate<UUID>> cands = new ArrayList<>();
		for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
			if (sp.getUUID().equals(l.player) || sp.level().dimension() != l.dim) continue;
			if (!ServerPlayNetworking.canSend(sp, Payloads.CrtView.TYPE)) continue;
			Integer proto = GbaServer.protocolOf(sp.getUUID());
			if (proto == null || proto != Payloads.PROTOCOL) continue;
			cands.add(new CrtViewers.Candidate<>(sp.getUUID(), Math.sqrt(sp.distanceToSqr(l.x, l.y + 0.6, l.z))));
		}
		CrtViewers.Selection<UUID> sel = CrtViewers.select(cands, cfg.maxViewers(), cfg.viewDistance());
		Map<UUID, Integer> next = new HashMap<>();
		for (UUID v : sel.viewers()) next.put(v, Payloads.VIEW_PLAYING);
		for (UUID v : sel.limited()) next.put(v, Payloads.VIEW_LIMIT);
		java.util.Set<UUID> audioNext = new java.util.HashSet<>();
		double audioRange = cfg.effectiveAudioDistance();
		for (CrtViewers.Candidate<UUID> c : cands) if (sel.limited().contains(c.id()) && c.distance() <= audioRange) audioNext.add(c.id());
		boolean newViewer = false;
		for (Map.Entry<UUID, Integer> en : next.entrySet()) {
			Integer prev = l.viewerState.get(en.getKey());
			if (prev == null || !prev.equals(en.getValue())) {
				sendView(en.getKey(), l, en.getValue());
				sendVolume(en.getKey(), l);
				if (en.getValue() == Payloads.VIEW_PLAYING) newViewer = true;
			}
		}
		for (UUID old : l.viewerState.keySet()) if (!next.containsKey(old)) sendView(old, l, Payloads.VIEW_NONE);
		boolean changed = !next.equals(l.viewerState) || !audioNext.equals(l.audioOnly);
		l.viewerState = next;
		l.audioOnly = audioNext;
		if (changed) rebuildRelays();
		if (newViewer) requestKeyframe(l.lastKeyframe, relays.get(l.player));
	}

	private void rebuildRelays() {
		Map<UUID, Relay> m = new HashMap<>();
		for (Live l : live.values()) {
			ServerPlayer streamer = server.getPlayerList().getPlayer(l.player);
			if (streamer == null) continue;
			List<ServerGamePacketListenerImpl> to = new ArrayList<>();
			for (Map.Entry<UUID, Integer> e : l.viewerState.entrySet()) {
				if (e.getValue() != Payloads.VIEW_PLAYING) continue;
				ServerPlayer v = server.getPlayerList().getPlayer(e.getKey());
				if (v != null) to.add(v.connection);
			}
			List<ServerGamePacketListenerImpl> ao = new ArrayList<>();
			for (UUID id : l.audioOnly) {
				ServerPlayer v = server.getPlayerList().getPlayer(id);
				if (v != null) ao.add(v.connection);
			}
			m.put(l.player, new Relay(l.crtId, streamer.connection, List.copyOf(to), List.copyOf(ao), l.bucket, l.lastKeyframe, l.bytes));
		}
		relays = Map.copyOf(m);
	}

	/** The player at the TV changed their volume: remember it and tell everyone who hears the TV. */
	void onVolume(ServerPlayer p, UUID crtId, int percent) {
		Live l = live.get(crtId);
		if (l == null || !l.player.equals(p.getUUID())) return;
		l.volume = Math.max(0, Math.min(100, percent));
		for (UUID v : l.viewerState.keySet()) sendVolume(v, l);
	}

	private void sendVolume(UUID to, Live l) {
		ServerPlayer sp = server.getPlayerList().getPlayer(to);
		if (sp != null && ServerPlayNetworking.canSend(sp, Payloads.CrtVolume.TYPE)) ServerPlayNetworking.send(sp, new Payloads.CrtVolume(l.crtId, l.volume));
	}

	void onNeedKeyframe(ServerPlayer p, UUID crtId) {
		Live l = live.get(crtId);
		if (l == null || !Integer.valueOf(Payloads.VIEW_PLAYING).equals(l.viewerState.get(p.getUUID()))) return;
		requestKeyframe(l.lastKeyframe, relays.get(l.player));
	}

	/** Thread safe, at most one request per 0.5 s per TV. */
	private static void requestKeyframe(AtomicLong last, Relay r) {
		if (r == null) return;
		long now = System.nanoTime(), prev = last.get();
		if (now - prev < KEYFRAME_GAP_NANOS || !last.compareAndSet(prev, now)) return;
		r.streamer().send(ServerPlayNetworking.createClientboundPacket(new Payloads.CrtKeyframeRequest(r.crtId())));
	}

	/** Called on the Netty thread (or the server thread) for every serverbound crt_stream. Thread safe. */
	public static void fastStream(ServerGamePacketListenerImpl listener, Payloads.CrtStreamC2S d) {
		Relay r = relays.get(listener.getPlayer().getUUID());
		if (r == null || !r.crtId().equals(d.crtId())) return;
		byte[] b = d.bytes();
		if (b.length < 1 || b.length > Payloads.MAX_STREAM || d.kind() < 0 || d.kind() > 1 || d.parts() < 1 || d.parts() > 64
			|| d.part() < 0 || d.part() >= d.parts()) return;
		boolean ok;
		synchronized (r.bucket()) {
			ok = r.bucket().take(b.length + 24);
		}
		if (!ok) { // over ~1 MB/s: drop and ask for a fresh keyframe so viewers recover
			if (d.kind() == 0) requestKeyframe(r.lastKeyframe(), r);
			return;
		}
		r.bytes().addAndGet(b.length);
		boolean audio = d.kind() == 1 && !r.audioOnly().isEmpty();
		if (r.viewers().isEmpty() && !audio) return;
		Packet<?> out = ServerPlayNetworking.createClientboundPacket(new Payloads.CrtStreamS2C(d.crtId(), d.seq(), d.kind(), d.part(), d.parts(), b));
		for (ServerGamePacketListenerImpl to : r.viewers()) to.send(out);
		if (audio) for (ServerGamePacketListenerImpl to : r.audioOnly()) to.send(out); // over the video cap, but close enough to hear
	}
}
