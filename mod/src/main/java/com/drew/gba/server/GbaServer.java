package com.drew.gba.server;

import com.drew.gba.GbaItems;
import com.drew.gba.net.Payloads;
import com.drew.gba.save.SaveRules;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Server-side behaviour: item interactions, pak burning, save storage, link rooms and relay. */
public final class GbaServer {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink");
	private static final double LINK_RANGE = 16.0;

	private final MinecraftServer server;
	private final LinkRooms<UUID> rooms = new LinkRooms<>();
	/** Player -> pak id they currently have open. */
	private final Map<UUID, UUID> sessions = new HashMap<>();
	private final Map<UUID, SaveAssembler> assemblers = new HashMap<>();
	private final Map<UUID, TokenBucket> saveLimit = new HashMap<>();
	private final Map<UUID, TokenBucket> burnLimit = new HashMap<>();
	private final Map<UUID, Long> lastSaveWrite = new HashMap<>();
	private final Map<UUID, SaveAssembler> importAssemblers = new HashMap<>();
	/** Player -> the pak they just burned and may still import an initial save into. */
	private final Map<UUID, PendingBurn> pendingBurns = new HashMap<>();
	/** Protocol number each player's client announced in Hello (absent = no hello yet). */
	private static final Map<UUID, Integer> clientProtocol = new java.util.concurrent.ConcurrentHashMap<>();
	private record PendingBurn(UUID pakId, long at) {}
	private SaveStore store;
	/** Last link_state sent per player (dedupe). */
	private final Map<UUID, EffectiveLink.State> lastSent = new HashMap<>();
	private final Map<UUID, Integer> cableCooldown = new HashMap<>();
	private int tickCounter;

	final CrtServer crt;

	private GbaServer(MinecraftServer server) {
		this.server = server;
		this.crt = new CrtServer(this, server);
	}

	private static GbaServer instance;

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Hello.TYPE, (p, ctx) -> {
			clientProtocol.put(ctx.player().getUUID(), p.protocol());
			if (ServerPlayNetworking.canSend(ctx.player(), Payloads.Welcome.TYPE)) {
				ServerPlayNetworking.send(ctx.player(), new Payloads.Welcome(Payloads.PROTOCOL));
			}
			if (p.protocol() != Payloads.PROTOCOL) {
				LOG.info("{} has GBA Link protocol {} (server: {})", ctx.player().getName().getString(), p.protocol(), Payloads.PROTOCOL);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.ImportChunk.TYPE, (p, ctx) -> with(ctx.server(), s -> s.onImportChunk(ctx.player(), p)));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Burn.TYPE, (p, ctx) -> with(ctx.server(), s -> s.onBurn(ctx.player(), p)));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SaveChunk.TYPE, (p, ctx) -> with(ctx.server(), s -> s.onSaveChunk(ctx.player(), p)));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Close.TYPE, (p, ctx) -> with(ctx.server(), s -> s.onClose(ctx.player(), p)));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.LinkDataC2S.TYPE, (p, ctx) -> fastRelay(ctx.player().connection, p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.CrtStreamC2S.TYPE, (p, ctx) -> CrtServer.fastStream(ctx.player().connection, p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.CrtClose.TYPE, (p, ctx) -> with(ctx.server(), s -> s.crt.onCrtClose(ctx.player(), p.crtId())));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.CrtNeedKeyframe.TYPE, (p, ctx) -> with(ctx.server(), s -> s.crt.onNeedKeyframe(ctx.player(), p.crtId())));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
			CrtServer.config = com.drew.gba.crt.CrtConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("gbalink.properties"));
			LOG.info("CRT settings: crt_max_viewers={}, crt_view_distance={}", CrtServer.config.maxViewers(), CrtServer.config.viewDistance());
		});
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			if (!GbaItems.is(sp.getItemInHand(hand), GbaItems.CRT)) return InteractionResult.PASS;
			return with(sp.level().getServer()).crt.place(sp, hit);
		});
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof net.minecraft.world.entity.Interaction i)) return InteractionResult.PASS;
			return with(sp.level().getServer()).crt.onPunch(sp, i) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			return with(sp.level().getServer()).onUseItem(sp, hand);
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			// allays would duplicate on an amethyst shard (eating the GBA) or take the item
			if (entity instanceof net.minecraft.world.entity.animal.allay.Allay && GbaItems.isOurs(player.getItemInHand(hand))) {
				return InteractionResult.FAIL;
			}
			if (!level.isClientSide() && player instanceof ServerPlayer csp && entity instanceof net.minecraft.world.entity.Interaction i && CrtServer.isCrt(i)) {
				return with(csp.level().getServer()).crt.onUse(csp, hand, i);
			}
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof ServerPlayer target)) {
				return InteractionResult.PASS;
			}
			return with(sp.level().getServer()).onUseCable(sp, target, hand);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			GbaServer s = instance;
			if (s != null) s.onDisconnect(handler.getPlayer());
		});
		ServerTickEvents.END_SERVER_TICK.register(srv -> {
			GbaServer s = instance;
			if (s != null) s.tick();
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(srv -> instance = null);
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> registerCommands(dispatcher));
	}

	private static GbaServer with(MinecraftServer srv) {
		GbaServer s = instance;
		if (s == null || s.server != srv) instance = s = new GbaServer(srv);
		return s;
	}

	private static void with(MinecraftServer srv, java.util.function.Consumer<GbaServer> c) {
		c.accept(with(srv));
	}

	// ------------------------------------------------------------------ items

	static void hint(ServerPlayer p, String msg) {
		p.sendOverlayMessage(Component.literal(msg));
	}

	private InteractionResult onUseItem(ServerPlayer p, InteractionHand hand) {
		ItemStack stack = p.getItemInHand(hand);
		String type = GbaItems.type(stack);
		if (type == null) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) {
			// Anything in the off hand is inert, but swallow the click so base items never act.
			return GbaItems.isType(type) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		switch (type) {
			case GbaItems.GBA -> useGba(p, stack);
			case GbaItems.BLANK_PAK -> {
				if (!ServerPlayNetworking.canSend(p, Payloads.Open.TYPE)) hint(p, "Install the GBA Link client mod to burn games");
			}
			case GbaItems.CABLE -> hint(p, "Right-click another player to link up");
			case GbaItems.CRT -> hint(p, "Right-click a block to place the TV");
			default -> { }
		}
		return GbaItems.isType(type) ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	private void useGba(ServerPlayer p, ItemStack gba) {
		PakData inserted = GbaItems.insertedPak(gba);
		ItemStack off = p.getItemInHand(InteractionHand.OFF_HAND);
		if (p.isShiftKeyDown()) {
			if (inserted == null) {
				PakData pak = GbaItems.pakOf(off);
				if (pak == null) { hint(p, "Hold a Game Pak in your off hand and sneak-click to insert it"); return; }
				GbaItems.insertPak(gba, pak);
				p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
				hint(p, "Inserted " + pak.title());
			} else {
				if (!off.isEmpty()) { hint(p, "Empty your off hand to eject the pak"); return; }
				closeSessionFor(p);
				ItemStack pakItem = GbaItems.createPak(inserted);
				GbaItems.ejectPak(gba);
				p.setItemInHand(InteractionHand.OFF_HAND, pakItem);
				hint(p, "Ejected " + inserted.title());
			}
			return;
		}
		if (inserted == null) { hint(p, "No Game Pak inserted. Sneak-click with a pak in your off hand."); return; }
		if (!ServerPlayNetworking.canSend(p, Payloads.Open.TYPE)) {
			hint(p, "Install the GBA Link client mod to play");
			return;
		}
		Integer proto = clientProtocol.get(p.getUUID());
		if (proto == null || proto != Payloads.PROTOCOL) {
			hint(p, proto != null && proto > Payloads.PROTOCOL
				? "The server's GBA Link mod is older than yours - ask the admin to update it"
				: "Your GBA Link mod is out of date - update the mod to play");
			return;
		}
		byte[] save = store().read(inserted.pakId());
		sessions.put(p.getUUID(), inserted.pakId());
		assemblers.remove(p.getUUID());
		ServerPlayNetworking.send(p, new Payloads.Open(inserted.pakId(), inserted.sha1(), inserted.title(), save, SaveRules.revision(save)));
		refreshLink(p.getUUID()); // after Open so the client's session exists when link_state arrives
	}

	private void closeSessionFor(ServerPlayer p) {
		crt.endForPlayer(p.getUUID());
		sessions.remove(p.getUUID());
		assemblers.remove(p.getUUID());
		refreshLink(p.getUUID());
	}

	private void onBurn(ServerPlayer p, Payloads.Burn b) {
		TokenBucket tb = burnLimit.computeIfAbsent(p.getUUID(), k -> new TokenBucket(3, 0.5));
		if (!tb.take(1)) { LOG.info("burn from {} rate limited", p.getName().getString()); return; }
		LOG.info("burn request from {} (main hand: {})", p.getName().getString(), GbaItems.type(p.getItemInHand(InteractionHand.MAIN_HAND)));
		String sha1 = b.romSha1() == null ? "" : b.romSha1().toLowerCase(java.util.Locale.ROOT);
		if (!PakData.validSha1(sha1)) { hint(p, "Invalid game data"); return; }
		InteractionHand hand = null;
		if (GbaItems.is(p.getItemInHand(InteractionHand.MAIN_HAND), GbaItems.BLANK_PAK)) hand = InteractionHand.MAIN_HAND;
		else if (GbaItems.is(p.getItemInHand(InteractionHand.OFF_HAND), GbaItems.BLANK_PAK)) hand = InteractionHand.OFF_HAND;
		if (hand == null) { hint(p, "Hold a Blank Game Pak to burn a game"); return; }
		String title = PakData.sanitizeTitle(b.title());
		if (title.isEmpty()) title = "Unknown game";
		UUID id = new UUID(ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
		PakData pak = new PakData(id, sha1, title, PakData.sanitizeCode(b.gameCode()));
		p.setItemInHand(hand, GbaItems.createPak(pak));
		pendingBurns.put(p.getUUID(), new PendingBurn(id, System.currentTimeMillis()));
		importAssemblers.remove(p.getUUID());
		hint(p, "Burned " + title + " onto a Game Pak");
		LOG.info("{} burned pak {} ({}, {})", p.getName().getString(), id, title, sha1);
	}

	// ------------------------------------------------------------------ saves

	private Path savesDir() {
		return server.getWorldPath(LevelResource.ROOT).resolve("gbalink").resolve("saves");
	}

	static Integer protocolOf(UUID id) {
		return clientProtocol.get(id);
	}

	/** Marks {@code pakId} as the pak this player has open (so its save uploads are accepted). */
	void openSession(ServerPlayer p, UUID pakId) {
		sessions.put(p.getUUID(), pakId);
		assemblers.remove(p.getUUID());
	}

	void refreshLinkOf(UUID id) {
		refreshLink(id);
	}

	SaveStore store() {
		if (store == null) store = new SaveStore(savesDir());
		return store;
	}

	private void ack(ServerPlayer p, UUID pakId, String rev, boolean accepted, String msg) {
		if (ServerPlayNetworking.canSend(p, Payloads.SaveAck.TYPE)) {
			ServerPlayNetworking.send(p, new Payloads.SaveAck(pakId, rev, accepted, msg));
		}
	}

	private void onSaveChunk(ServerPlayer p, Payloads.SaveChunk c) {
		UUID me = p.getUUID();
		if (!c.pakId().equals(sessions.get(me))) return; // no open session for this pak
		TokenBucket tb = saveLimit.computeIfAbsent(me, k -> new TokenBucket(1024 * 1024, 256 * 1024));
		if (!tb.take(Math.max(1, c.bytes().length))) { assemblers.remove(me); return; }
		SaveAssembler a = assemblers.computeIfAbsent(me, k -> new SaveAssembler());
		byte[] done;
		try {
			done = a.accept(c.offset(), c.total(), c.bytes());
		} catch (IllegalArgumentException e) {
			assemblers.remove(me);
			LOG.debug("Rejected save chunk from {}: {}", p.getName().getString(), e.getMessage());
			return;
		}
		if (done == null) return;
		long now = System.currentTimeMillis();
		Long last = lastSaveWrite.get(me);
		if (last != null && now - last < 200) { // at most ~5 completed saves per second
			ack(p, c.pakId(), store().revision(c.pakId()), false, "too fast");
			return;
		}
		lastSaveWrite.put(me, now);
		try {
			String rev = store().write(c.pakId(), done);
			ack(p, c.pakId(), rev, true, "");
		} catch (IOException e) {
			LOG.warn("Failed to write save for pak {}", c.pakId(), e);
			ack(p, c.pakId(), store().revision(c.pakId()), false, "write failed");
		}
	}

	/** Initial save for the pak just burned. Accepted once, only for that pak, only while it has no save. */
	private void onImportChunk(ServerPlayer p, Payloads.ImportChunk c) {
		UUID me = p.getUUID();
		PendingBurn pb = pendingBurns.get(me);
		if (pb == null) {
			hint(p, "Import refused: burn a pak first");
			return;
		}
		SaveAssembler a = importAssemblers.computeIfAbsent(me, k -> new SaveAssembler());
		byte[] done;
		try {
			done = a.accept(c.offset(), c.total(), c.bytes());
		} catch (IllegalArgumentException e) {
			importAssemblers.remove(me);
			return;
		}
		if (done == null) return;
		importAssemblers.remove(me);
		String why = SaveRules.importRejection(true, System.currentTimeMillis() - pb.at(), store().has(pb.pakId()), done.length);
		if (why != null) {
			pendingBurns.remove(me);
			hint(p, "Import refused: " + why);
			ack(p, pb.pakId(), store().revision(pb.pakId()), false, why);
			return;
		}
		pendingBurns.remove(me);
		try {
			String rev = store().write(pb.pakId(), done);
			hint(p, "Imported save (" + done.length + " bytes) into the new pak");
			ack(p, pb.pakId(), rev, true, "");
			LOG.info("{} imported a {} byte save into pak {}", p.getName().getString(), done.length, pb.pakId());
		} catch (IOException e) {
			LOG.warn("Failed to write imported save for pak {}", pb.pakId(), e);
			hint(p, "Import failed");
		}
	}

	private void onClose(ServerPlayer p, Payloads.Close c) {
		if (c.pakId().equals(sessions.get(p.getUUID()))) closeSessionFor(p);
	}

	// ------------------------------------------------------------------ link cable

	private InteractionResult onUseCable(ServerPlayer user, ServerPlayer target, InteractionHand hand) {
		if (!GbaItems.is(user.getItemInHand(hand), GbaItems.CABLE)) return InteractionResult.PASS;
		int now = tickCounter;
		Integer last = cableCooldown.get(user.getUUID());
		if (last != null && now - last < 10) return InteractionResult.SUCCESS;
		cableCooldown.put(user.getUUID(), now);
		if (!ServerPlayNetworking.canSend(user, Payloads.LinkState.TYPE)) {
			hint(user, "Install the GBA Link client mod to use link cables");
			return InteractionResult.SUCCESS;
		}
		if (!ServerPlayNetworking.canSend(target, Payloads.LinkState.TYPE)) {
			hint(user, target.getName().getString() + " doesn't have the GBA Link mod");
			return InteractionResult.SUCCESS;
		}
		if (user.distanceToSqr(target) > LINK_RANGE * LINK_RANGE) {
			hint(user, "Too far away");
			return InteractionResult.SUCCESS;
		}
		List<LinkRooms.Update<UUID>> updates = new ArrayList<>();
		LinkRooms.LinkResult r = rooms.link(user.getUUID(), target.getUUID(), updates);
		switch (r) {
			case LINKED -> hint(user, "Linked with " + target.getName().getString());
			case ALREADY_LINKED -> hint(user, "Already linked");
			case ROOM_FULL -> hint(user, "Link room is full (4 players)");
			case OTHER_ROOM -> hint(user, "One of you is already in another link room (/gba unlink)");
			case SELF -> { }
		}
		if (r == LinkRooms.LinkResult.LINKED) {
			target.sendOverlayMessage(Component.literal("Linked with " + user.getName().getString()));
		}
		publish(updates);
		return InteractionResult.SUCCESS;
	}

	private void unlink(UUID id) {
		List<LinkRooms.Update<UUID>> updates = new ArrayList<>();
		rooms.remove(id, updates);
		publish(updates);
	}

	private void publish(List<LinkRooms.Update<UUID>> updates) {
		rebuildRoutes();
		for (LinkRooms.Update<UUID> u : updates) {
			sendRoom(u.room());
			for (UUID id : u.released()) sendLinkState(id, EffectiveLink.State.UNPLUGGED, List.of());
			// cabled but not running a GBA: tell them how to join
			for (UUID id : u.room()) {
				if (!sessions.containsKey(id)) hintId(id, "Cable linked - open your GBA to join the link");
			}
		}
	}

	private boolean hasSession(UUID id) {
		return sessions.containsKey(id);
	}

	/** Recompute and resend the effective link for the room {@code id} is in. */
	private void refreshLink(UUID id) {
		rebuildRoutes();
		List<UUID> room = rooms.room(id);
		if (!room.isEmpty()) sendRoom(room);
	}

	private void sendRoom(List<UUID> room) {
		Map<UUID, EffectiveLink.State> states = EffectiveLink.states(room, this::hasSession);
		List<String> names = new ArrayList<>();
		for (UUID id : EffectiveLink.active(room, this::hasSession)) names.add(nameOf(id));
		for (Map.Entry<UUID, EffectiveLink.State> e : states.entrySet()) {
			sendLinkState(e.getKey(), e.getValue(), e.getValue().plugged() ? names : List.of());
		}
	}

	private void hintId(UUID id, String msg) {
		ServerPlayer sp = server.getPlayerList().getPlayer(id);
		if (sp != null) hint(sp, msg);
	}

	private String nameOf(UUID id) {
		ServerPlayer sp = server.getPlayerList().getPlayer(id);
		return sp == null ? "?" : sp.getName().getString();
	}

	private void sendLinkState(UUID id, EffectiveLink.State st, List<String> names) {
		if (st.equals(lastSent.get(id)) || (st.equals(EffectiveLink.State.UNPLUGGED) && !lastSent.containsKey(id))) {
			lastSent.put(id, st);
			return;
		}
		lastSent.put(id, st);
		ServerPlayer sp = server.getPlayerList().getPlayer(id);
		if (sp != null && ServerPlayNetworking.canSend(sp, Payloads.LinkState.TYPE)) {
			ServerPlayNetworking.send(sp, new Payloads.LinkState(st.index(), st.count(), names));
		}
	}

	// ---- link data fast path
	//
	// Fabric (like vanilla since 1.21.x) defers every serverbound play packet to the next server tick, which
	// made each link transfer cost two 50 ms tick waits (~10 transfers/s). Link data is therefore relayed
	// straight from the Netty thread by LinkDataFastPathMixin using an immutable routing snapshot that the
	// server thread republishes whenever rooms or sessions change.

	private record Route(int from, List<ServerGamePacketListenerImpl> to) {}

	private static volatile Map<UUID, Route> routes = Map.of();
	private static final Map<UUID, TokenBucket> fastLimit = new java.util.concurrent.ConcurrentHashMap<>();

	private void rebuildRoutes() {
		Map<UUID, Route> m = new HashMap<>();
		for (List<UUID> room : rooms.allRooms()) {
			List<UUID> active = EffectiveLink.active(room, this::hasSession);
			for (int i = 0; i < active.size(); i++) {
				List<ServerGamePacketListenerImpl> to = new ArrayList<>();
				for (int j = 0; j < active.size(); j++) {
					if (j == i) continue;
					ServerPlayer sp = server.getPlayerList().getPlayer(active.get(j));
					if (sp != null) to.add(sp.connection);
				}
				m.put(active.get(i), new Route(i, List.copyOf(to)));
			}
		}
		routes = Map.copyOf(m);
	}

	/** Called on the Netty thread (or the server thread) for every serverbound link_data. Thread safe. */
	public static void fastRelay(ServerGamePacketListenerImpl listener, Payloads.LinkDataC2S d) {
		byte[] bytes = d.bytes();
		if (bytes.length < 1 || bytes.length > Payloads.MAX_LINK) return;
		UUID id = listener.getPlayer().getUUID();
		Route r = routes.get(id);
		if (r == null) return; // not cabled, or not part of the effective link
		TokenBucket tb = fastLimit.computeIfAbsent(id, k -> new TokenBucket(5000, 5000));
		synchronized (tb) {
			if (!tb.take(1)) return;
		}
		net.minecraft.network.protocol.Packet<?> out = ServerPlayNetworking.createClientboundPacket(new Payloads.LinkDataS2C(r.from(), bytes));
		if (RELAY_DELAY_MS > 0) {
			// dev-only latency simulation (-Dgbalink.relaydelay=<ms>); FIFO because the executor is single threaded
			DELAYER.schedule(() -> { for (ServerGamePacketListenerImpl to : r.to()) to.send(out); }, RELAY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
			return;
		}
		for (ServerGamePacketListenerImpl to : r.to()) to.send(out);
	}

	private static final long RELAY_DELAY_MS = Long.getLong("gbalink.relaydelay", 0L);
	private static final java.util.concurrent.ScheduledExecutorService DELAYER = RELAY_DELAY_MS > 0
		? java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "gbalink-relay-delay"); t.setDaemon(true); return t; })
		: null;

	private void onDisconnect(ServerPlayer p) {
		UUID id = p.getUUID();
		crt.onDisconnect(id);
		sessions.remove(id); // unlink() below republishes the room (and routes) without this player
		assemblers.remove(id);
		lastSent.remove(id);
		saveLimit.remove(id);
		fastLimit.remove(id);
		burnLimit.remove(id);
		lastSaveWrite.remove(id);
		importAssemblers.remove(id);
		pendingBurns.remove(id);
		clientProtocol.remove(id);
		cableCooldown.remove(id);
		unlink(id);
	}

	private void tick() {
		tickCounter++;
		crt.tick();
		if (tickCounter % 20 != 0) return;
		for (List<UUID> room : rooms.allRooms()) {
			for (UUID id : room) {
				ServerPlayer me = server.getPlayerList().getPlayer(id);
				boolean ok = false;
				if (me != null) {
					for (UUID other : room) {
						if (other.equals(id)) continue;
						ServerPlayer o = server.getPlayerList().getPlayer(other);
						if (o != null && o.level() == me.level() && o.distanceToSqr(me) <= LINK_RANGE * LINK_RANGE) { ok = true; break; }
					}
				}
				if (!ok) {
					if (me != null) hint(me, "Link cable disconnected (out of range)");
					unlink(id);
					break; // room changed; re-evaluate next second
				}
			}
		}
	}

	// ------------------------------------------------------------------ commands

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("gba")
			.then(Commands.literal("unlink").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				GbaServer s = with(ctx.getSource().getServer());
				if (!s.rooms.isLinked(p.getUUID())) {
					ctx.getSource().sendFailure(Component.literal("You are not linked to anyone"));
					return 0;
				}
				s.unlink(p.getUUID());
				ctx.getSource().sendSuccess(() -> Component.literal("Link cable removed"), false);
				return 1;
			}))
			.then(Commands.literal("info").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				GbaServer s = with(ctx.getSource().getServer());
				List<UUID> room = s.rooms.room(p.getUUID());
				String modded = ServerPlayNetworking.canSend(p, Payloads.Open.TYPE) ? "yes" : "no (install the GBA Link client mod)";
				StringBuilder sb = new StringBuilder("GBA Link: client mod " + modded);
				if (room.size() >= 2) {
					sb.append(". Linked as P").append(room.indexOf(p.getUUID()) + 1).append(" of ").append(room.size()).append(": ");
					for (int i = 0; i < room.size(); i++) sb.append(i > 0 ? ", " : "").append(s.nameOf(room.get(i)));
				} else {
					sb.append(". Not linked.");
				}
				String msg = sb.toString();
				ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
				return 1;
			}))
			.then(Commands.literal("give")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("item", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						new String[] {GbaItems.GBA, GbaItems.BLANK_PAK, GbaItems.CABLE, GbaItems.CRT}, b))
					.executes(ctx -> give(ctx.getSource(), StringArgumentType.getString(ctx, "item"), ctx.getSource().getPlayerOrException()))
					.then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> give(ctx.getSource(), StringArgumentType.getString(ctx, "item"),
							EntityArgument.getPlayer(ctx, "player")))))));
	}

	private static int give(CommandSourceStack src, String type, ServerPlayer target) {
		if (!(type.equals(GbaItems.GBA) || type.equals(GbaItems.BLANK_PAK) || type.equals(GbaItems.CABLE) || type.equals(GbaItems.CRT))) {
			src.sendFailure(Component.literal("Unknown item '" + type + "' (gba, blank_pak, cable, crt)"));
			return 0;
		}
		ItemStack stack = GbaItems.create(type);
		if (!target.getInventory().add(stack)) target.drop(stack, false, net.minecraft.util.Prediction.PREDICTED);
		src.sendSuccess(() -> Component.literal("Gave " + type + " to " + target.getName().getString()), true);
		return 1;
	}
}
