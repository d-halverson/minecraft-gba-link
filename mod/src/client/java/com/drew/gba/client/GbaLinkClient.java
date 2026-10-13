package com.drew.gba.client;

import com.drew.gba.GbaItems;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.ui.GbaScreen;
import com.drew.gba.client.ui.RomPickerScreen;
import com.drew.gba.net.Payloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Client entrypoint: networking, keybind, and session lifecycle. */
public class GbaLinkClient implements ClientModInitializer {
	private static KeyMapping burnKey;

	private static EmulatorSession current;
	private static int linkIndex, linkCount;
	private static List<String> linkNames = List.of();

	@Override
	public void onInitializeClient() {
		RomLibrary.init(FabricLoader.getInstance().getGameDir());
		// Dev-only end-to-end scenario driver. Completely inert (class never loaded) without the property.
		if (System.getProperty("gbalink.autotest") != null) AutoTest.start();

		burnKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.gbalink.burn", InputConstants.KEY_G,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("gbalink", "main"))));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Open.TYPE, (p, ctx) -> onOpen(ctx.client(), p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.LinkState.TYPE, (p, ctx) -> {
			linkIndex = p.playerIndex();
			linkCount = p.playerCount();
			linkNames = List.copyOf(p.names());
			EmulatorSession s = current;
			if (s != null) s.onLinkState(linkIndex, linkCount, linkNames);
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
			if (ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
				ClientPlayNetworking.send(new Payloads.Hello(Payloads.PROTOCOL));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			EmulatorSession s = current;
			if (s != null) {
				s.abort();
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
		Path gameDir = FabricLoader.getInstance().getGameDir();
		EmulatorSession old = current;
		if (old != null) {
			old.stopAndWait(1000);
			current = null;
		}
		RomLibrary.Rom rom = RomLibrary.findBySha1(p.romSha1());
		if (rom == null) {
			closeOnServer(p.pakId());
			client.gui.setScreen(new GbaScreen(List.of("You don't have this game (" + p.title() + ").",
				"Put the .gba file in .minecraft/gbalink/roms/")));
			return;
		}
		GbaNative natives = GbaNative.get(gameDir);
		if (natives == null) {
			closeOnServer(p.pakId());
			client.gui.setScreen(new GbaScreen(List.of(GbaNative.loadError() == null ? "Emulator unavailable." : GbaNative.loadError(),
				"Platform: " + GbaNative.platform())));
			return;
		}
		Path state = RomLibrary.baseDir().resolve("states").resolve(rom.sha1() + ".state");
		EmulatorSession s = new EmulatorSession(natives, p.pakId(), p.title(), rom.path(), state, p.save(),
			linkIndex, linkCount, linkNames);
		current = s;
		s.start();
		client.gui.setScreen(new GbaScreen(s));
	}

	private static void closeOnServer(UUID pakId) {
		try {
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
	}
}
