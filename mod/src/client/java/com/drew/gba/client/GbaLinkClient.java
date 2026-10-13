package com.drew.gba.client;

import com.drew.gba.GbaItems;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.emu.GbaNative;
import com.drew.gba.client.ui.GbaScreen;
import com.drew.gba.client.ui.RomPickerScreen;
import com.drew.gba.net.Payloads;
import com.drew.gba.save.SaveRules;
import com.drew.gba.save.SuspendFile;
import com.drew.gba.server.SaveStore;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Client entrypoint: networking, keybind, and session lifecycle. */
public class GbaLinkClient implements ClientModInitializer {
	private static KeyMapping burnKey;

	private static EmulatorSession current;
	/** A session that was closed but is still flushing its save / writing the suspend state. */
	private static volatile EmulatorSession closing;
	/** Protocol the server announced in Welcome; 0 = not (yet) announced, i.e. an old server. */
	private static int serverProtocol;
	/** Save bytes sent for the pak that is being burned right now (to mirror them when the server acks). */
	private static byte[] pendingImport;
	private static int linkIndex, linkCount;
	private static List<String> linkNames = List.of();

	@Override
	public void onInitializeClient() {
		RomLibrary.init(FabricLoader.getInstance().getGameDir());
		RomLibrary.ensureFolders(); // gbalink/roms (+README) and gbalink/imports exist from the first start
		// Dev-only end-to-end scenario driver. Completely inert (class never loaded) without the property.
		if (System.getProperty("gbalink.autotest") != null) AutoTest.start();

		burnKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.gbalink.burn", InputConstants.KEY_G,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("gbalink", "main"))));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Open.TYPE, (p, ctx) -> onOpen(ctx.client(), p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Welcome.TYPE, (p, ctx) -> {
			serverProtocol = p.protocol();
			if (p.protocol() > Payloads.PROTOCOL) {
				ctx.client().gui.hud.setOverlayMessage(Component.literal("GBA Link: the server is newer than your mod - update the mod"), false);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.SaveAck.TYPE, (p, ctx) -> onSaveAck(p));
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
			serverProtocol = 0;
			if (ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
				ClientPlayNetworking.send(new Payloads.Hello(Payloads.PROTOCOL));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
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
			old.stopAndWait(3500);
			current = null;
		}
		EmulatorSession fin = closing;
		if (fin != null) {
			fin.stopAndWait(3500); // its suspend state must be on disk before we decide whether to resume
			closing = null;
		}
		if (p.revision().isEmpty()) { // protocol 1 server: it cannot tell us save revisions
			closeOnServer(p.pakId());
			client.gui.setScreen(new GbaScreen(List.of("This server's GBA Link mod is out of date.", "Ask the admin to update the mod.")));
			return;
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
		Path state = migrateState(p.pakId(), rom.sha1());
		Path suspend = RomLibrary.suspendDir().resolve(p.pakId() + ".state");
		Resume r = planResume(suspend, p.revision(), rom.sha1());
		EmulatorSession s = new EmulatorSession(natives, new EmulatorSession.Config(p.pakId(), p.title(), rom.sha1(), rom.path(),
			state, suspend, RomLibrary.savesDir().resolve(p.pakId() + ".sav"), RomLibrary.exportsDir(), p.save(), p.revision(),
			r.state, r.toast, linkIndex, linkCount, linkNames));
		current = s;
		s.start();
		client.gui.setScreen(new GbaScreen(s));
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
		byte[] imp = pendingImport; // ack of an import: keep a local copy too
		if (imp != null && p.accepted() && SaveRules.revision(imp).equals(p.revision())) {
			pendingImport = null;
			try {
				SaveStore.writeAtomic(RomLibrary.savesDir().resolve(p.pakId() + ".sav"), imp);
			} catch (IOException ignored) {
			}
		}
	}

	/** Whether the server can take an imported save (protocol 2+). */
	public static boolean serverSupportsImport() {
		return serverProtocol >= 2;
	}

	/** Burn {@code rom}; if {@code importSave} is non-null, upload it right after as the new pak's first save. */
	public static void burn(RomLibrary.Rom rom, byte[] importSave) {
		ClientPlayNetworking.send(new Payloads.Burn(rom.sha1(), rom.name(), rom.code()));
		if (importSave == null) return;
		pendingImport = importSave;
		int off = 0;
		do {
			int len = Math.min(Payloads.MAX_CHUNK, importSave.length - off);
			ClientPlayNetworking.send(new Payloads.ImportChunk(off, importSave.length, java.util.Arrays.copyOfRange(importSave, off, off + len)));
			off += len;
		} while (off < importSave.length);
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
		if (!s.isFinished()) closing = s;
	}
}
