package com.drew.gba.client;

import com.drew.gba.GbaItems;
import com.drew.gba.client.crt.CrtClient;
import com.drew.gba.client.crt.CrtStreamer;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.netplay.NetplayEngine;
import com.drew.gba.client.ui.CrtScreen;
import com.drew.gba.client.ui.GbaScreen;
import com.drew.gba.client.ui.RomPickerScreen;
import com.drew.gba.net.Payloads;
import com.drew.gba.save.SaveRules;
import com.drew.gba.save.SuspendFile;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Client entrypoint: networking, keybind, and session lifecycle. */
public class GbaLinkClient implements ClientModInitializer {
	private static KeyMapping burnKey;

	private static volatile EmulatorSession current;
	/** A session that was closed but is still flushing its save / writing the suspend state. */
	private static volatile EmulatorSession closing;
	/** Protocol the server announced in Welcome; 0 = not (yet) announced, i.e. an old server. */
	private static int serverProtocol;
	/** Save bytes sent for the pak that is being burned right now (to mirror them when the server acks). */
	private static int linkIndex, linkCount;
	private static List<String> linkNames = List.of();

	/** Short, comparable ROM identity: the first 8 hex digits of its SHA-1. */
	public static String romId(String sha1) {
		return sha1 == null || sha1.length() < 8 ? "?" : sha1.substring(0, 8).toLowerCase(java.util.Locale.ROOT);
	}

	@Override
	public void onInitializeClient() {
		RomLibrary.init(FabricLoader.getInstance().getGameDir());
		RomLibrary.ensureFolders(); // gbalink/roms (+README) and gbalink/imports exist from the first start
		// Dev-only end-to-end scenario driver. Completely inert (class never loaded) without the property.
		if (System.getProperty("gbalink.autotest") != null) AutoTest.start();

		// ROM ID (first 8 hex digits of the ROM's SHA-1) on paks and loaded GBAs, so players can check they have the
		// same ROM before linking (netplay needs it).
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			com.drew.gba.server.PakData pak = GbaItems.is(stack, GbaItems.PAK) ? GbaItems.pakOf(stack)
				: GbaItems.is(stack, GbaItems.GBA) ? GbaItems.insertedPak(stack) : null;
			if (pak == null) return;
			if (GbaItems.is(stack, GbaItems.GBA) && !pak.title().isEmpty())
				lines.add(Component.literal("Game: " + pak.title()).withStyle(net.minecraft.ChatFormatting.GRAY));
			lines.add(Component.literal("ROM ID: " + romId(pak.sha1())).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		});

		burnKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.gbalink.burn", InputConstants.KEY_G,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("gbalink", "main"))));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Open.TYPE, (p, ctx) -> onOpen(ctx.client(), p));
		CrtClient.init();
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CrtOpen.TYPE, (p, ctx) -> onCrtOpen(ctx.client(), p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CrtView.TYPE, (p, ctx) -> CrtClient.onView(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CrtStreamS2C.TYPE, (p, ctx) -> CrtClient.onStream(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CrtKeyframeRequest.TYPE, (p, ctx) -> {
			EmulatorSession s = current;
			if (s != null && s.streamer() != null) s.streamer().requestKeyframe();
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Welcome.TYPE, (p, ctx) -> {
			serverProtocol = p.protocol();
			if (p.protocol() > Payloads.PROTOCOL) {
				ctx.client().gui.hud.setOverlayMessage(Component.literal("GBA Link: the server is newer than your mod - update the mod"), false);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.SaveAck.TYPE, (p, ctx) -> onSaveAck(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.NetplayStart.TYPE, (p, ctx) -> {
			EmulatorSession s = current;
			if (s != null) s.onNetplayStart(new NetplayEngine.Params(p.sessionId(), p.index(), p.count(), List.copyOf(p.names()), p.epoch(), p.tzOffset(), p.delay()));
		});
		// netplay_input / netplay_hash / netplay_snapshot are taken on the network thread by ClientNetplayFastPathMixin;
		// these receivers only run if that mixin is absent (they must be registered for the payload types to be handled)
		ClientPlayNetworking.registerGlobalReceiver(Payloads.NetplayInputS2C.TYPE, (p, ctx) -> fastNetplayInput(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.NetplayHashS2C.TYPE, (p, ctx) -> fastNetplayHash(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.NetplaySnapshotS2C.TYPE, (p, ctx) -> fastNetplaySnapshot(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.LinkState.TYPE, (p, ctx) -> {
			linkIndex = p.playerIndex();
			linkCount = p.playerCount();
			linkNames = List.copyOf(p.names());
			EmulatorSession s = current;
			if (s != null) s.onLinkState(linkIndex, linkCount, linkNames, p.mode());
			if (p.playerCount() >= 2) {
				ctx.client().gui.hud.setOverlayMessage(Component.literal("Link cable: P" + (p.playerIndex() + 1) + " of "
					+ p.playerCount() + " (" + String.join(", ", p.names()) + ")"), false);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.LinkDataS2C.TYPE, (p, ctx) -> {
			EmulatorSession s = current;
			if (s != null) s.onLinkData(p.fromIndex(), p.bytes());
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			linkIndex = 0;
			linkCount = 0;
			linkNames = List.of();
			serverProtocol = 0;
			CrtClient.reset();
			if (ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
				ClientPlayNetworking.send(new Payloads.Hello(Payloads.PROTOCOL, netplayCaps()));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			CrtClient.reset();
			EmulatorSession s = current;
			if (s != null) {
				s.abort();
				s.stopAndWait(2000); // writes the suspend state (marked unconfirmed) even though we cannot upload
				current = null;
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (burnKey.consumeClick()) {
				if (client.player == null || client.gui.screen() != null) continue;
				openBurnScreen(client);
			}
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			ItemStack stack = player.getItemInHand(hand);
			if (GbaItems.is(stack, GbaItems.BLANK_PAK) && ClientPlayNetworking.canSend(Payloads.Burn.TYPE)) {
				Minecraft.getInstance().execute(() -> openBurnScreen(Minecraft.getInstance()));
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}

	/** Network thread (or game thread): netplay messages go straight to the session's controller. */
	public static void fastNetplayInput(Payloads.NetplayInputS2C p) {
		EmulatorSession s = current;
		if (s != null) s.netplay().onInput(p.sessionId(), p.from(), p.firstFrame(), p.recs(), p.sendMs(), p.echo());
	}

	public static void fastNetplayHash(Payloads.NetplayHashS2C p) {
		EmulatorSession s = current;
		if (s != null) s.netplay().onHash(p.sessionId(), p.from(), p.gen(), p.frame(), p.hash());
	}

	public static void fastNetplaySnapshot(Payloads.NetplaySnapshotS2C p) {
		EmulatorSession s = current;
		if (s != null) s.netplay().onSnapshot(p.sessionId(), p.from(), p.gen(), p.frame(), p.offset(), p.total(), p.bytes());
	}

	/** CAP_NETPLAY if the native library has the virtual RTC (API 2). Loads the library, which is cheap and cached. */
	private static int netplayCaps() {
		try {
			GbaNative n = GbaNative.get(FabricLoader.getInstance().getGameDir());
			return n != null && n.supportsNetplay() ? Payloads.Hello.CAP_NETPLAY : 0;
		} catch (Throwable t) {
			return 0;
		}
	}

	private static void openBurnScreen(Minecraft client) {
		if (client.player == null || !ClientPlayNetworking.canSend(Payloads.Burn.TYPE)) return;
		boolean has = GbaItems.is(client.player.getItemInHand(InteractionHand.MAIN_HAND), GbaItems.BLANK_PAK)
			|| GbaItems.is(client.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.BLANK_PAK);
		if (!has) {
			client.gui.hud.setOverlayMessage(Component.literal("Hold a Blank Game Pak to burn a game"), false);
			return;
		}
		client.gui.setScreen(new RomPickerScreen());
	}

	private static void onOpen(Minecraft client, Payloads.Open p) {
		openGame(client, p.pakId(), p.romSha1(), p.title(), p.save(), p.revision(), null);
	}

	private static void onCrtOpen(Minecraft client, Payloads.CrtOpen p) {
		openGame(client, p.pakId(), p.romSha1(), p.title(), p.save(), p.revision(), p);
	}

	/** Shared by the GBA item and the CRT TV: prepare the session (resume / save revision guard) and show its screen. */
	private static void openGame(Minecraft client, UUID pakId, String romSha1, String title, byte[] save, String revision, Payloads.CrtOpen crt) {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		EmulatorSession old = current;
		if (old != null) {
			old.stopAndWait(3500);
			current = null;
		}
		EmulatorSession fin = closing;
		if (fin != null) {
			fin.stopAndWait(3500); // its suspend state must be on disk before we decide whether to resume
			closing = null;
		}
		if (revision.isEmpty()) { // protocol 1 server: it cannot tell us save revisions
			closeOnServer(pakId, crt);
			client.gui.setScreen(new GbaScreen(List.of("This server's GBA Link mod is out of date.", "Ask the admin to update the mod.")));
			return;
		}
		RomLibrary.Rom rom = RomLibrary.findBySha1(romSha1);
		if (rom == null) {
			closeOnServer(pakId, crt);
			client.gui.setScreen(new GbaScreen(List.of("You don't have this game (" + title + ").",
				"Put the .gba file in .minecraft/gbalink/roms/")));
			return;
		}
		GbaNative natives = GbaNative.get(gameDir);
		if (natives == null) {
			closeOnServer(pakId, crt);
			client.gui.setScreen(new GbaScreen(List.of(GbaNative.loadError() == null ? "Emulator unavailable." : GbaNative.loadError(),
				"Platform: " + GbaNative.platform())));
			return;
		}
		Path state = migrateState(pakId, rom.sha1());
		Path suspend = RomLibrary.suspendDir().resolve(pakId + ".state");
		Resume r = planResume(suspend, revision, rom.sha1());
		EmulatorSession s = new EmulatorSession(natives, new EmulatorSession.Config(pakId, title, rom.sha1(), rom.path(),
			state, suspend, RomLibrary.savesDir().resolve(pakId + ".sav"), RomLibrary.exportsDir(), save, revision,
			r.state, r.toast, linkIndex, linkCount, linkNames));
		current = s;
		if (crt != null) {
			CrtClient.beginOwn(crt.crtId(), crt.x(), crt.y(), crt.z(), crt.yaw(), s);
			s.attachStreamer(new CrtStreamer(crt.crtId()));
		}
		s.start();
		client.gui.setScreen(crt != null ? new CrtScreen(s, crt.crtId()) : new GbaScreen(s));
	}

	private record Resume(byte[] state, String toast) {}

	/** Reads the suspend file (if any), applies the consistency guard, and deletes it when it must not be used. */
	static Resume planResume(Path suspend, String serverRevision, String romSha1) {
		SuspendFile sf = null;
		boolean unreadable = false;
		if (Files.isRegularFile(suspend)) {
			try {
				sf = SuspendFile.decode(Files.readAllBytes(suspend));
				unreadable = sf == null;
			} catch (IOException e) {
				unreadable = true;
			}
		}
		SaveRules.Resume d = SaveRules.decide(sf, unreadable, serverRevision, romSha1);
		switch (d) {
			case RESUME:
				return new Resume(sf.state(), "");
			case NONE:
				return new Resume(null, "Powered on");
			default:
				try {
					Files.deleteIfExists(suspend);
				} catch (IOException ignored) {
				}
				return new Resume(null, d == SaveRules.Resume.DISCARD_CHANGED
					? "Save changed elsewhere - started fresh from the latest save"
					: "Suspended game was invalid - started fresh");
		}
	}

	/** F5/F8 states are per pak. A state file from before (keyed by the ROM's SHA-1) is copied over on first use. */
	private static Path migrateState(UUID pakId, String romSha1) {
		Path dir = RomLibrary.statesDir();
		Path state = dir.resolve(pakId + ".state");
		Path legacy = dir.resolve(romSha1 + ".state");
		try {
			if (!Files.exists(state) && Files.isRegularFile(legacy)) {
				Files.createDirectories(dir);
				Files.copy(legacy, state);
			}
		} catch (IOException ignored) {
		}
		return state;
	}

	private static void onSaveAck(Payloads.SaveAck p) {
		EmulatorSession s = current;
		if (s == null || !s.pakId.equals(p.pakId())) s = closing;
		if (s != null && s.pakId.equals(p.pakId())) {
			s.onSaveAck(p.revision(), p.accepted());
			return;
		}
	}

	/** Burn {@code rom} onto the held Blank Game Pak. */
	public static void burn(RomLibrary.Rom rom) {
		ClientPlayNetworking.send(new Payloads.Burn(rom.sha1(), rom.name(), rom.code()));
	}

	private static void closeOnServer(UUID pakId, Payloads.CrtOpen crt) {
		try {
			if (crt != null) ClientPlayNetworking.send(new Payloads.CrtClose(crt.crtId()));
			ClientPlayNetworking.send(new Payloads.Close(pakId));
		} catch (Throwable ignored) {
		}
	}

	/** Current emulator session, or null (used by the dev AutoTest). */
	public static EmulatorSession currentSession() {
		return current;
	}

	public static int linkCount() {
		return linkCount;
	}

	/** Called by the screen when it closes its session. */
	public static void sessionEnded(EmulatorSession s) {
		if (current == s) current = null;
		if (!s.isFinished()) closing = s;
	}
}
