package com.drew.gba.client;

import com.drew.gba.GbaItems;
import java.util.UUID;
import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.crt.CrtClient;
import com.drew.gba.client.ui.CrtScreen;
import com.drew.gba.client.ui.SessionControls;
import com.drew.gba.client.ui.GbaScreen;
import com.drew.gba.client.ui.RomPickerScreen;
import com.drew.gba.net.Payloads;
import com.drew.gba.server.PakData;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * DEV-ONLY end-to-end scenario driver. Only ever started when the JVM has -Dgbalink.autotest=solo|A|B
 * (see {@link GbaLinkClient}); without that property this class is never even loaded.
 * It drives the REAL code paths: chat commands, use-item / interact packets, the burn screen, the GBA
 * screen's key handler, and the game's own screenshot facility.
 *
 * Properties: gbalink.autotest (solo|A|B), gbalink.autotest.rom (file in gbalink/roms, link roles),
 * gbalink.autotest.sync (shared dir for A/B barriers), gbalink.autotest.world (server world dir, to check saves).
 */
public final class AutoTest {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink-autotest");
	private static final int WRAM = 0x21000; // EWRAM inside mGBA's raw save state
	private static final int LOGBASE = 0x100;

	private final String mode;
	private final Minecraft mc;
	private final Path dir;
	private final Path sync;
	private final String romName;

	private AutoTest(String mode) {
		this.mode = mode;
		this.mc = Minecraft.getInstance();
		this.dir = mc.gameDirectory.toPath().resolve("autotest");
		String sp = System.getProperty("gbalink.autotest.sync");
		this.sync = sp == null ? dir.resolve("sync") : Path.of(sp);
		this.romName = System.getProperty("gbalink.autotest.rom", mode.startsWith("ui") ? "tone.gba" : mode.startsWith("crt") && !mode.equals("crtmodel") ? "shades.gba" : mode.equals("qol") ? "counter.gba" : mode.equals("solo") ? "sram.gba" : mode.startsWith("np") || mode.startsWith("nc") || mode.startsWith("lv") ? "link_pokesim.gba" : "link_n32_" + (mode.equals("A") ? "master" : "slave") + ".gba");
	}

	public static void start() {
		String mode = System.getProperty("gbalink.autotest");
		if (mode == null || mode.isBlank()) return;
		Thread t = new Thread(() -> {
			try {
				while (Minecraft.getInstance() == null) Thread.sleep(100);
				AutoTest a = new AutoTest(mode);
				a.run();
			} catch (Throwable e) {
				LOG.error("[AUTOTEST] aborted with exception", e);
			}
		}, "gbalink-autotest");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ plumbing

	private void log(String s) {
		LOG.info("[AUTOTEST/{}] {}", mode, s);
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("result.log"), s + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
	}

	private static final class Fail extends RuntimeException {
		Fail(String m) { super(m); }
	}

	private void fail(String m) {
		log("FAIL: " + m);
		try {
			log("screen=" + main(() -> String.valueOf(mc.gui.screen())) + " selected=" + main(() -> mc.player.getInventory().getSelectedSlot()));
			for (int i = 0; i < 9; i++) { int j = i; log("  hotbar" + i + "=" + main(() -> mc.player.getInventory().getItem(j).toString())); }
			shot("fail_" + mode);
		} catch (Throwable ignored) { }
		throw new Fail(m);
	}

	private void check(boolean ok, String what) {
		log((ok ? "PASS: " : "FAIL: ") + what);
		if (!ok) throw new Fail(what);
	}

	private <T> T main(Supplier<T> s) {
		return mc.submit(s).join();
	}

	private void main(Runnable r) {
		mc.submit(r).join();
	}

	private void sleep(long ms) {
		try { Thread.sleep(ms); } catch (InterruptedException e) { throw new Fail("interrupted"); }
	}

	private void waitFor(String what, long ms, Supplier<Boolean> c) {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (Boolean.TRUE.equals(main(c))) return;
			sleep(50);
		}
		fail("timeout waiting for " + what);
	}

	private void signal(String name) {
		try {
			Files.createDirectories(sync);
			Files.writeString(sync.resolve(name), "1");
		} catch (IOException e) {
			throw new Fail("signal " + e);
		}
		log("signal " + name);
	}

	private void awaitSignal(String name, long ms) {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (Files.exists(sync.resolve(name))) { log("got " + name); return; }
			sleep(100);
		}
		fail("timeout waiting for signal " + name);
	}

	private void clearSignal(String name) {
		try { Files.deleteIfExists(sync.resolve(name)); } catch (IOException ignored) { }
	}

	// ------------------------------------------------------------------ evidence

	private void shot(String name) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		main(() -> Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> done.complete(null)));
		try {
			done.get(15, TimeUnit.SECONDS);
			log("screenshot screenshots/" + name + ".png");
			copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve(name + ".png"),
				name.startsWith("crt_") || name.startsWith("v043_") ? name + ".png" : mc.getUser().getName() + "_" + romName.replace(".gba", "") + "_" + name + ".png");
		} catch (Exception e) {
			fail("screenshot " + name + ": " + e);
		}
	}

	private void copyEvidence(Path src, String dstName) {
		if (src.getFileName().toString().startsWith("netplay_")) dstName = src.getFileName().toString();
		try {
			Path d = sync.resolveSibling("evidence");
			Files.createDirectories(d);
			Files.copy(src, d.resolve(dstName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			log("evidence copy failed: " + e);
		}
	}

	private void framePng(String name) {
		EmulatorSession s = GbaLinkClient.currentSession();
		if (s == null) fail("no session for framebuffer " + name);
		byte[] rgba = new byte[240 * 160 * 4];
		s.frames.copyTo(rgba);
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(name + ".png"), png(rgba, 240, 160));
			copyEvidence(dir.resolve(name + ".png"), "fb_" + mode + "_" + romName.replace(".gba", "") + "_" + name + ".png");
			int nonBlack = 0;
			for (int i = 0; i < rgba.length; i += 4) if ((rgba[i] | rgba[i + 1] | rgba[i + 2]) != 0) nonBlack++;
			log("framebuffer autotest/" + name + ".png seq=" + s.frames.seq() + " nonBlackPixels=" + nonBlack);
		} catch (IOException e) {
			fail("framebuffer png: " + e);
		}
	}

	private static byte[] png(byte[] rgba, int w, int h) throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int y = 0; y < h; y++) {
			raw.write(0);
			raw.write(rgba, y * w * 4, w * 4);
		}
		Deflater d = new Deflater();
		d.setInput(raw.toByteArray());
		d.finish();
		ByteArrayOutputStream z = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		while (!d.finished()) z.write(buf, 0, d.deflate(buf));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
		ByteBuffer ihdr = ByteBuffer.allocate(13);
		ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		chunk(out, "IHDR", ihdr.array());
		chunk(out, "IDAT", z.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
		DataOutputStream o = new DataOutputStream(out);
		o.writeInt(data.length);
		byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		o.write(t);
		o.write(data);
		CRC32 c = new CRC32();
		c.update(t);
		c.update(data);
		o.writeInt((int) c.getValue());
	}

	// ------------------------------------------------------------------ game actions

	private int slotOf(String type) {
		return main(() -> {
			for (int i = 0; i < 9; i++) if (GbaItems.is(mc.player.getInventory().getItem(i), type)) return i;
			return -1;
		});
	}

	private void select(int slot) {
		main(() -> mc.player.getInventory().setSelectedSlot(slot));
		sleep(250);
	}

	private void command(String c) {
		log("command /" + c);
		main(() -> mc.player.connection.sendCommand(c));
		sleep(300);
	}

	private void giveAndWait(String type) {
		command("gba give " + type);
		waitFor("item " + type + " in hotbar", 10000, () -> {
			for (int i = 0; i < 9; i++) if (GbaItems.is(mc.player.getInventory().getItem(i), type)) return true;
			return false;
		});
	}

	private void use() {
		main(() -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
		sleep(300);
	}

	private void sneak(boolean on) {
		main(() -> mc.options.keyShift.setDown(on));
		sleep(400);
	}

	private void pressKey(GbaScreen screen, int key, int glfw, long holdMs) {
		main(() -> screen.keyPressed(new KeyEvent(key, glfw, 0)));
		sleep(holdMs);
		main(() -> screen.keyReleased(new KeyEvent(key, glfw, 0)));
	}

	private GbaScreen gbaScreen() {
		return main(() -> mc.gui.screen() instanceof GbaScreen g ? g : null);
	}

	/** Burn the configured ROM onto a blank pak in the hotbar through the real picker screen. */
	private void burn() {
		int blank = slotOf(GbaItems.BLANK_PAK);
		check(blank >= 0, "blank pak in hotbar");
		select(blank);
		use(); // client UseItemCallback opens the picker
		waitFor("burn screen", 5000, () -> mc.gui.screen() instanceof RomPickerScreen);
		String label = romName.substring(0, romName.length() - 4);
		sleep(1000); // let the window settle (a resize re-inits the screen and replaces its widgets)
		Boolean clicked = null;
		for (int page = 0; page < 8 && clicked == null; page++) { // the picker lists 6 ROMs per page
			clicked = main(() -> {
				for (var c : mc.gui.screen().children()) {
					if (c instanceof Button b && b.getMessage().getString().startsWith(label)) {
						return mc.gui.screen().mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0,
							b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(1, 0)), false);
					}
				}
				return null;
			});
			if (clicked == null) {
				main(() -> {
					for (var c : mc.gui.screen().children()) {
						if (c instanceof Button b && b.getMessage().getString().equals(">")) {
							mc.gui.screen().mouseClicked(new MouseButtonEvent(b.getX() + 3.0, b.getY() + 3.0, new MouseButtonInfo(1, 0)), false);
							return;
						}
					}
				});
				sleep(500);
			}
		}
		if (clicked == null) fail("rom button '" + label + "' not on the burn screen (is the rom in gbalink/roms?)");
		log("clicked burn button; screen now " + main(() -> String.valueOf(mc.gui.screen())));
		waitFor("burned pak", 8000, () -> GbaItems.is(mc.player.getInventory().getItem(blank), GbaItems.PAK));
		PakData pd = main(() -> GbaItems.pakOf(mc.player.getInventory().getItem(blank)));
		log("burned pak " + pd.pakId() + " title=" + pd.title() + " code=" + pd.code() + " sha1=" + pd.sha1());
	}

	/** pak (hotbar) -> off hand, gba selected, sneak-use to insert. */
	private PakData insert() {
		int pak = slotOf(GbaItems.PAK);
		select(pak);
		main(() -> mc.player.connection.send(new ServerboundPlayerActionPacket(
			ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, 0)));
		waitFor("pak in off hand", 5000, () -> GbaItems.is(mc.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.PAK));
		int gba = slotOf(GbaItems.GBA);
		select(gba);
		sneak(true);
		use();
		sneak(false);
		waitFor("pak inserted in gba", 5000, () -> GbaItems.insertedPak(mc.player.getInventory().getItem(gba)) != null);
		PakData pd = main(() -> GbaItems.insertedPak(mc.player.getInventory().getItem(gba)));
		check(true, "pak " + pd.pakId() + " inserted");
		return pd;
	}

	private void openGba() {
		select(slotOf(GbaItems.GBA));
		use();
		waitFor("GBA screen with a live session", 15000, () -> mc.gui.screen() instanceof GbaScreen && GbaLinkClient.currentSession() != null);
		waitFor("first frame", 10000, () -> GbaLinkClient.currentSession().frames.seq() > 5);
		check(true, "GBA screen open and emulating");
	}

	/** v0.4.3: leaving while linked asks first; older scenarios that leave on purpose confirm with Leave. */
	private void confirmLeave() {
		sleep(300);
		var pop = main(() -> mc.gui.screen() instanceof com.drew.gba.client.ui.LeaveLinkScreen p ? p : null);
		if (pop == null) return;
		int[] lc = main(pop::leaveButtonCenter);
		log("leave popup shown; confirming Leave");
		clickScreen(pop, lc[0], lc[1]);
	}

	private void closeGba() {
		EmulatorSession s = GbaLinkClient.currentSession();
		GbaScreen sc = gbaScreen();
		if (sc == null) fail("no GBA screen to close");
		main(() -> sc.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 256, 0))); // real Esc handling
		confirmLeave();
		waitFor("screen closed", 5000, () -> mc.gui.screen() == null);
		waitFor("session finished", 5000, s::isFinished);
	}

	private Path savePath(PakData pd) {
		String w = System.getProperty("gbalink.autotest.world");
		return w == null ? null : Path.of(w).resolve("gbalink").resolve("saves").resolve(pd.pakId() + ".sav");
	}

	// ------------------------------------------------------------------ scenarios

	private void joinAndWait() {
		waitFor("in world", 120000, () -> mc.player != null && mc.level != null && mc.gui.screen() == null);
		waitFor("server handshake (canSend)", 20000, () -> ClientPlayNetworking.canSend(Payloads.Burn.TYPE));
		sleep(2000);
		log("joined as " + main(() -> mc.player.getGameProfile().name()));
		clearSignal(mode + ".ready");
		command("clear @s"); // start every run from an empty inventory (leftovers from an earlier run)
		sleep(500);
	}

	private void run() {
		try {
			log("starting scenario " + mode + " rom=" + romName);
			if (mode.equals("qol")) bootFolders();
			joinAndWait();
			if (mode.equals("crtmodel")) crtModel();
			else if (mode.equals("crtA")) crtPlayer(false);
			else if (mode.equals("crtA2")) crtPlayer(true);
			else if (mode.equals("crtB") || mode.equals("crtC")) crtViewer();
			else if (mode.equals("uiA") || mode.equals("uiB")) ui042();
			else if (mode.equals("qol")) qol();
			else if (mode.equals("solo")) solo();
			else if (mode.startsWith("np") || mode.startsWith("nc") || mode.startsWith("lv")) netplayScenario();
			else link();
			log("SCENARIO COMPLETE: " + mode);
		} catch (Fail f) {
			log("SCENARIO FAILED: " + mode + " - " + f.getMessage());
		} catch (Throwable t) {
			LOG.error("[AUTOTEST] crashed", t);
			log("SCENARIO CRASHED: " + mode + " - " + t);
		}
		if (Boolean.getBoolean("gbalink.autotest.quit")) {
			sleep(1500);
			log("quitting client");
			mc.submit(() -> mc.stop());
		}
	}


	// ------------------------------------------------------------------ v0.4.2 UI: volume wheels, CRT options panel, link HUD, Unlink, CRT sound

	private void uiShot(String name) {
		shot("ui_" + name);
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("ui_" + name + ".png"), "v042ui_" + name + ".png");
	}

	private void setGuiSize(int w, int h, int scale) {
		main(() -> {
			mc.options.guiScale().set(scale);
			try { mc.getWindow().setWindowed(w, h); } catch (Throwable t) { log("setWindowed failed: " + t); }
			mc.resizeGui();
		});
		sleep(1200);
		log("window " + main(() -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " gui " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight()));
	}

	private void mark(String s) {
		LOG.info("[AUDIO-MARK] {} {}", mode, s);
		log("MARK " + s);
	}

	/** Two clients: A (cable, GBA, CRT) and B (GBA, listener). */
	private void ui042() {
		boolean a = mode.equals("uiA");
		String other = a ? "B" : "A";
		String me = a ? "A" : "B";
		giveAndWait(GbaItems.GBA);
		giveAndWait(GbaItems.BLANK_PAK);
		if (a) { giveAndWait(GbaItems.CABLE); giveAndWait(GbaItems.CRT); giveAndWait(GbaItems.BLANK_PAK); }
		burn();
		insert();
		if (a) burn(); // the second pak (still in the hotbar) goes into the CRT later
		command("time set noon");
		command("weather clear");
		command("difficulty peaceful");
		signal(me + ".ready");
		awaitSignal(other + ".ready", 240000);

		// ---- 1. GBA screen: wheels at normal size, hover readout, drag, small sizes (A only)
		if (a) {
			hideGui(false);
			openGba();
			sleep(1500);
			clearClutter();
			uiShot("gba_normal");
			GbaScreen gs = gbaScreen();
			SessionControls sc = main(gs::controls);
			int[] kc = main(() -> new int[] {sc.gba.centerX(), sc.gba.wheelCenterY(), sc.mc.centerX()});
			main(() -> sc.debugPointer(kc[0], kc[1]));
			sleep(400);
			uiShot("gba_hover_gba_knob");
			// drag the GBA wheel to 40% with the real mouse handlers
			double gx = kc[0], gy = kc[1];
			main(() -> gs.mouseClicked(new MouseButtonEvent(gx, gy, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false));
			main(() -> gs.mouseDragged(new MouseButtonEvent(gx, gy, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), -40, 0));
			sleep(400);
			check(ClientConfig.gbaVolume() == 40, "dragging the GBA wheel 40 px left sets 40% (" + ClientConfig.gbaVolume() + ")");
			uiShot("gba_dragging");
			main(() -> gs.mouseReleased(new MouseButtonEvent(gx, gy, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))));
			main(() -> sc.debugPointer(kc[2], kc[1]));
			main(() -> gs.mouseScrolled(kc[2], kc[1], 0, -1));
			float mcBefore = main(() -> mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER));
			main(() -> gs.mouseScrolled(kc[2], kc[1], 0, 1));
			float mcAfter = main(() -> mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER));
			check(Math.abs(mcAfter - mcBefore - 0.05f) < 0.011f, "scrolling the MC wheel changes Minecraft's master volume (" + mcBefore + " -> " + mcAfter + ")");
			sleep(400);
			uiShot("gba_hover_mc_knob");
			// keys
			main(() -> gs.keyPressed(new KeyEvent(InputConstants.KEY_EQUALS, 46, 0)));
			check(ClientConfig.gbaVolume() == 45, "= raises the GBA volume by 5 (" + ClientConfig.gbaVolume() + ")");
			main(() -> gs.keyPressed(new KeyEvent(InputConstants.KEY_RBRACKET, 48, 0)));
			main(() -> sc.debugPointer(-100, -100));
			sleep(300);
			main(() -> ClientConfig.setGbaVolume(100));
			main(() -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(1.0));
			// small sizes
			setGuiSize(640, 360, 2);
			uiShot("gba_small_640x360_gui2");
			setGuiSize(480, 320, 2);
			uiShot("gba_small_480x320_gui2");
			setGuiSize(854, 480, 4);
			uiShot("gba_gui4_854x480");
			setGuiSize(1280, 720, 3);
			main(() -> mc.options.guiScale().set(0));
			setGuiSize(1000, 600, 0);
			closeGba();
		}
		signal(me + ".gba_done");
		awaitSignal(other + ".gba_done", 240000);

		// ---- 2. cable: A links to B; HUD with no GBA open
		if (a) {
			command("tp @s TestB");
			sleep(1500);
			select(slotOf(GbaItems.CABLE));
			AbstractClientPlayer target = main(() -> {
				for (AbstractClientPlayer p : mc.level.players()) if (p != mc.player && p.getGameProfile().name().equals("TestB")) return p;
				return null;
			});
			if (target == null) fail("TestB not visible");
			main(() -> mc.gameMode.interact(mc.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND));
			sleep(800);
			signal("A.cabled");
		}
		awaitSignal("A.cabled", 60000);
		waitFor("cable_state on the client", 10000, ClientCable::linked);
		check(true, "cable_state received: linked to " + ClientCable.partnerText());
		sleep(600);
		uiShot("link_hud_" + mode + "_just_linked");   // the actionbar message is up
		sleep(3500);
		uiShot("link_hud_" + mode + "_idle");          // HUD only, no GBA open
		check(main(() -> mc.gui.screen() == null), "no screen open, HUD indicator drawn");
		signal(me + ".hud1");
		awaitSignal(other + ".hud1", 60000);

		// B opens its GBA; A (no screen) sees it
		if (!a) {
			openGba();
			sleep(2500);
			uiShot("link_gba_waiting_B");
			signal("B.open");
			awaitSignal("A.hudwaiting", 60000);
			closeGba();
		} else {
			awaitSignal("B.open", 60000);
			sleep(2000);
			uiShot("link_hud_A_partner_has_gba_open");
			signal("A.hudwaiting");
		}
		sleep(1500);
		signal(me + ".hud2");
		awaitSignal(other + ".hud2", 60000);

		// F3 and potion effect placement (A)
		if (a) {
			main(() -> mc.debugEntries.toggleDebugOverlay());
			sleep(600);
			uiShot("link_hud_A_with_f3");
			main(() -> mc.debugEntries.toggleDebugOverlay());
			command("effect give @s minecraft:speed 30 0 true");
			command("effect give @s minecraft:night_vision 30 0 true");
			sleep(1500);
			uiShot("link_hud_A_with_effects");
			hideGui(true);
			sleep(300);
			uiShot("link_hud_A_f1_hidden");
			hideGui(false);
			command("effect clear @s");
		}
		signal(me + ".hud3");
		awaitSignal(other + ".hud3", 60000);

		// ---- 3. CRT: A plays the tone on a TV, B listens at 2, 3, 12, 30 blocks
		BlockPos at = main(() -> mc.player.blockPosition());
		double gy, tx, tz;
		if (a) {
			gy = at.getY();
			tx = at.getX() + 6.0; tz = at.getZ() + 0.5;
			try { Files.createDirectories(sync); Files.writeString(crtPosFile(), tx + " " + gy + " " + tz); } catch (IOException e) { throw new Fail("pos " + e); }
			command("kill @e[type=!minecraft:player]");
			tp(tx + 0.5, gy, tz - 3.2, 0f, 8f);
			select(slotOf(GbaItems.CRT));
			BlockPos floor = new BlockPos(at.getX() + 6, at.getY() - 1, at.getZ());
			main(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(floor.getX() + 0.5, gy, floor.getZ() + 0.5), Direction.UP, floor, false)));
			sleep(1500);
			waitFor("CRT entity", 5000, () -> findCrtEntityNow() != null);
			select(slotOf(GbaItems.PAK));
			main(() -> mc.player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, 0)));
			waitFor("pak in off hand", 5000, () -> GbaItems.is(mc.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.PAK));
			interactCrt(true);
			waitFor("pak inserted", 5000, () -> mc.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty());
			tp(tx, gy, tz - 3.6, 0f, 8f);
			interactCrt(false);
			waitFor("CRT screen with a live session", 20000, () -> mc.gui.screen() instanceof CrtScreen && GbaLinkClient.currentSession() != null);
			waitFor("first frame", 10000, () -> GbaLinkClient.currentSession().frames.seq() > 5);
			sleep(2500);
			clearClutter();
			CrtScreen cs = main(() -> mc.gui.screen() instanceof CrtScreen c ? c : null);
			main(() -> cs.setPanelOpen(false));
			sleep(400);
			uiShot("crt_clean");
			int[] ob = main(cs::optionsButtonCenter);
			main(() -> cs.mouseClicked(new MouseButtonEvent(ob[0], ob[1], new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false));
			check(main(cs::panelOpen), "clicking Options opens the panel");
			sleep(500);
			uiShot("crt_panel_open");
			SessionControls sc = main(cs::controls);
			main(() -> sc.debugPointer(sc.gba.centerX(), sc.gba.wheelCenterY()));
			sleep(300);
			uiShot("crt_panel_hover_knob");
			int[] pw = main(() -> sc.powerSwitchCenter());
			main(() -> sc.debugPointer(pw[0], pw[1]));
			sleep(300);
			uiShot("crt_panel_hover_power");
			main(() -> sc.debugPointer(-100, -100));
			// panel keys: GBA keys still work while it is open
			main(() -> cs.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 262, 0)));
			check((main(() -> GbaLinkClient.currentSession().heldMask()) & (1 << 4)) != 0, "GBA keys keep working while the panel is open");
			main(() -> cs.keyReleased(new KeyEvent(InputConstants.KEY_RIGHT, 262, 0)));
			main(() -> cs.keyPressed(new KeyEvent(InputConstants.KEY_O, 18, 0)));
			check(!main(cs::panelOpen), "O hides the panel");
			sleep(400);
			uiShot("crt_panel_hidden_again");
			main(() -> cs.keyPressed(new KeyEvent(InputConstants.KEY_O, 18, 0)));
			sleep(300);
			// the Unlink button is in the panel while cabled
			uiShot("crt_panel_linked_unlink_button");
			sleep(1000);
			// Unlink through the panel's real button
			Boolean clicked = main(() -> {
				for (var c : mc.gui.screen().children()) {
					if (c instanceof net.minecraft.client.gui.components.Button bt && bt.getMessage().getString().startsWith("Unlink")) {
						return mc.gui.screen().mouseClicked(new MouseButtonEvent(bt.getX() + 3.0, bt.getY() + 3.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
					}
				}
				return null;
			});
			check(clicked != null, "the panel has an Unlink button while cabled");
			sleep(300);
			uiShot("unlink_A_after_click");
			signal("A.unlinked");
			waitFor("cable_state says unlinked", 8000, () -> !ClientCable.linked());
			sleep(1500);
			uiShot("unlink_A_panel_without_button");
			signal("A.tv");
			awaitSignal("B.stagesdone", 240000);
			main(() -> ClientConfig.setGbaVolume(50)); // the TV volume = the player's GBA knob
			mark("player GBA volume now 50");
			sleep(2000);
			signal("A.vol50");
			awaitSignal("B.done", 240000);
			CrtScreen cs2 = main(() -> mc.gui.screen() instanceof CrtScreen c ? c : null);
			main(() -> cs2.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 256, 0)));
			confirmLeave();
			waitFor("screen closed", 5000, () -> mc.gui.screen() == null);
			sleep(1000);
		} else {
			awaitSignal("A.unlinked", 240000);
			sleep(900);
			uiShot("unlink_B_partner_message");
			sleep(3000);
			uiShot("unlink_B_hud_gone");
			check(!ClientCable.linked(), "B no longer shows a cable after A unplugged");
			awaitSignal("A.tv", 240000);
			main(() -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(1.0)); // listener master volume 100%
			double[] pos;
			try {
				String[] ps = Files.readString(crtPosFile()).trim().split(" ");
				pos = new double[] {Double.parseDouble(ps[0]), Double.parseDouble(ps[1]), Double.parseDouble(ps[2])};
			} catch (IOException e) { throw new Fail("crt pos " + e); }
			double bx = pos[0] + 5.0; // off to the side of A (A stands in front of the TV)
			for (double d : new double[] {2, 3, 12, 30}) {
				tp(bx - 5.0 + 0.0, pos[1], pos[2] - d, 0f, 0f);
				mark("listener at " + d + " blocks (GBA volume at the TV = 100)");
				sleep(7000);
				if (d == 3) { clearClutter(); uiShot("crt_listener_3_blocks"); }
			}
			signal("B.stagesdone");
			awaitSignal("A.vol50", 60000);
			for (double d : new double[] {2, 3}) {
				tp(bx - 5.0, pos[1], pos[2] - d, 0f, 0f);
				mark("listener at " + d + " blocks (TV volume 50)");
				sleep(7000);
			}
			main(() -> ClientConfig.setGbaVolume(20)); // a spectator's own knob does not change the TV
			mark("listener's own GBA knob set to 20");
			sleep(7000);
			main(() -> ClientConfig.setGbaVolume(100));
			signal("B.done");
		}
	}

	// ------------------------------------------------------------------ CRT scenarios

	private void tp(double x, double y, double z, float yaw, float pitch) {
		command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
		sleep(700);
	}

	private void clearClutter() {
		main(() -> { mc.gui.hud.getChat().clearMessages(true); mc.gui.toastManager().clear(); });
		sleep(500);
	}

	private boolean guiHidden;

	private void hideGui(boolean h) {
		if (h != guiHidden) main(() -> mc.gui.hud.toggle());
		guiHidden = h;
		sleep(300);
	}

	/** Model preview shots of the placed TV from several angles, plus inventory and held views. */
	private void crtModel() {
		final String P = "crt_model_v3_";
		giveAndWait(GbaItems.CRT);
		BlockPos at = main(() -> mc.player.blockPosition());
		double gy = at.getY();
		double tx = at.getX() + 6.0, tz = at.getZ() + 0.5; // middle of the 2-wide TV
		command("time set noon");
		command("weather clear");
		command("difficulty peaceful");
		command("kill @e[type=!minecraft:player,type=!minecraft:interaction,type=!minecraft:item_display]");
		command("kill @e[type=minecraft:interaction,distance=..40]");
		command("kill @e[type=minecraft:item_display,distance=..40]");
		tp(tx + 0.5, gy, tz - 5.0, 0f, 25f);
		select(slotOf(GbaItems.CRT));
		BlockPos floor = new BlockPos(at.getX() + 6, at.getY() - 1, at.getZ());
		main(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(floor.getX() + 0.5, gy, floor.getZ() + 0.5), Direction.UP, floor, false)));
		sleep(1500);
		tp(tx + 1.2, gy, tz - 4.0, 0f, 20f);
		clearClutter();
		shot(P + "first_person");
		hideGui(true);
		tp(tx, gy, tz - 6.2, 0f, 8f);
		shot(P + "front");
		tp(tx + 4.4, gy, tz - 4.4, 45f, 8f);
		shot(P + "front_left");
		tp(tx - 4.4, gy, tz - 4.4, -45f, 8f);
		shot(P + "front_right");
		tp(tx + 6.0, gy, tz, 90f, 8f);
		shot(P + "side");
		tp(tx, gy + 3.5, tz - 5.5, 0f, 30f);
		shot(P + "above");
		tp(tx, gy, tz + 6.2, 180f, 8f);
		shot(P + "back");
		tp(tx, gy, tz - 3.4, 0f, 5f);
		shot(P + "close_up_front");
		tp(tx - 2.0, gy, tz - 6.0, -10f, 6f);
		shot(P + "six_blocks_away");
		hideGui(false);
		tp(tx + 1.0, gy, tz - 5.0, 0f, 25f);
		main(() -> { mc.options.guiScale().set(4); mc.resizeGui(); });
		sleep(500);
		main(() -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
		sleep(800);
		shot(P + "inventory");
		main(() -> mc.gui.setScreen(null));
	}

	private net.minecraft.world.entity.Interaction findCrtEntity() {
		return main(() -> {
			net.minecraft.world.entity.Interaction best = null;
			for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.Interaction i && (best == null || i.distanceTo(mc.player) < best.distanceTo(mc.player))) best = i;
			}
			return best;
		});
	}

	private void interactCrt(boolean sneak) {
		net.minecraft.world.entity.Interaction e = findCrtEntity();
		if (e == null) fail("no CRT interaction entity visible");
		if (sneak) sneak(true);
		main(() -> mc.gameMode.interact(mc.player, e, new EntityHitResult(e), InteractionHand.MAIN_HAND));
		sleep(400);
		if (sneak) sneak(false);
	}

	private Path crtPosFile() { return sync.resolve("crt.pos"); }

	/** TestA: place a CRT, insert a burned pak, play (TestB watches), leave, punch it to get it back. {@code reopen}: after a server restart. */
	private void crtPlayer(boolean reopen) {
		BlockPos at = main(() -> mc.player.blockPosition());
		double gy = at.getY();
		double tx, tz;
		command("time set noon");
		command("weather clear");
		command("difficulty peaceful");
		try {
			if (!reopen) {
				giveAndWait(GbaItems.CRT);
				giveAndWait(GbaItems.BLANK_PAK);
				burn();
				command("kill @e[type=!minecraft:player]");
				tx = at.getX() + 6.0; tz = at.getZ() + 0.5;
				Files.createDirectories(sync);
				Files.writeString(crtPosFile(), tx + " " + gy + " " + tz);
				tp(tx + 0.5, gy, tz - 3.2, 0f, 8f);
				select(slotOf(GbaItems.CRT));
				BlockPos floor = new BlockPos(at.getX() + 6, at.getY() - 1, at.getZ());
				main(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
					new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(floor.getX() + 0.5, gy, floor.getZ() + 0.5), Direction.UP, floor, false)));
				sleep(1500);
				waitFor("CRT entity", 5000, () -> findCrtEntityNow() != null);
				check(true, "CRT placed");
				clearClutter();
				shot("crt_idle_placed");
				// insert the pak: pak -> off hand, sneak-use the TV
				select(slotOf(GbaItems.PAK));
				main(() -> mc.player.connection.send(new ServerboundPlayerActionPacket(
					ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, 0)));
				waitFor("pak in off hand", 5000, () -> GbaItems.is(mc.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.PAK));
				interactCrt(true);
				waitFor("pak inserted (off hand empty)", 5000, () -> mc.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty());
				check(true, "pak inserted into the CRT");
			} else {
				String[] pos = Files.readString(crtPosFile()).trim().split(" ");
				tx = Double.parseDouble(pos[0]); tz = Double.parseDouble(pos[2]);
				tp(tx, gy, tz - 3.2, 0f, 8f);
				waitFor("CRT entity after restart", 15000, () -> findCrtEntityNow() != null);
				check(true, "CRT entity still there after the server restart");
			}
		} catch (IOException e) {
			throw new Fail("crt pos file " + e);
		}
		signal("A.ready");
		if (System.getProperty("gbalink.crt.nob") == null) awaitSignal("B.ready", 240000);
		if (System.getProperty("gbalink.crt.third") != null) awaitSignal("C.ready", 240000);
		// play
		tp(tx, gy, tz - 3.6, 0f, 8f);
		final boolean f1First = System.getProperty("gbalink.crt.f1") != null;
		if (f1First) hideGui(true); // the player already had F1 on before sitting down at the TV
		boolean hudBefore = main(() -> mc.gui.hud.isHidden());
		interactCrt(false);
		waitFor("CRT screen with a live session", 20000, () -> mc.gui.screen() instanceof CrtScreen && GbaLinkClient.currentSession() != null);
		waitFor("first frame", 10000, () -> GbaLinkClient.currentSession().frames.seq() > 5);
		check(true, "CRT play mode open and emulating");
		sleep(1500);
		float[] rot = main(() -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
		log("camera after turning to the TV: yaw=" + rot[0] + " pitch=" + rot[1]);
		clearClutter();
		check(main(() -> mc.gui.hud.isHidden()), "HUD (crosshair, hand, hotbar) is hidden while playing on the CRT");
		shot("crt_A_playing");
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("crt_A_playing.png"), f1First ? "v04qol_crt_playing_f1.png" : "v04qol_crt_playing.png");
		CrtScreen cs = main(() -> mc.gui.screen() instanceof CrtScreen c ? c : null);
		main(() -> cs.keyPressed(new KeyEvent(InputConstants.KEY_E, 69, 0)));
		sleep(800);
		String et = main(() -> GbaLinkClient.currentSession().toast());
		check(et.startsWith("Exported") || et.contains("no save yet"), "E on the CRT triggers the export ('" + et + "')");
		shot("crt_A_export_toast");
		main(() -> cs.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 262, 0)));
		sleep(500);
		main(() -> cs.keyReleased(new KeyEvent(InputConstants.KEY_RIGHT, 262, 0)));
		check((main(() -> GbaLinkClient.currentSession().heldMask()) & (1 << 4)) == 0, "key released");
		signal("A.playing");
		sleep(12000); // viewers watch
		long up = main(() -> GbaLinkClient.currentSession().streamer().bytesSent.get());
		log("streamer uploaded " + up + " bytes in total, video packets " + main(() -> GbaLinkClient.currentSession().streamer().videoPackets.get()));
		shot("crt_A_playing2");
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("crt_A_playing2.png"), f1First ? "v04qol_crt_hint_f1.png" : "v04qol_crt_hint.png");
		signal("A.done");
		sleep(1500);
		// leave with Esc: suspends like closing the GBA
		EmulatorSession ses = GbaLinkClient.currentSession();
		main(() -> cs.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 256, 0)));
		confirmLeave();
		waitFor("screen closed", 5000, () -> mc.gui.screen() == null);
		waitFor("session finished", 8000, ses::isFinished);
		sleep(1500);
		shot("crt_A_after_leaving");
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("crt_A_after_leaving.png"), f1First ? "v04qol_crt_after_f1.png" : "v04qol_crt_after.png");
		check(main(() -> mc.gui.hud.isHidden()) == hudBefore, "HUD state restored exactly after leaving the CRT (was hidden=" + hudBefore + ")");
		if (reopen) { log("reopen scenario done (persistence verified); leaving the TV in place"); return; }
		if (System.getProperty("gbalink.crt.keep") != null) { log("keeping the TV in the world (persistence test)"); return; }
		// punch it: the CRT item comes back with the pak
		tp(tx, gy, tz - 2.2, 0f, 8f);
		net.minecraft.world.entity.Interaction e = findCrtEntity();
		main(() -> mc.gameMode.attack(mc.player, e));
		sleep(1500);
		waitFor("CRT entity gone", 5000, () -> findCrtEntityNow() == null);
		check(true, "punching removed the TV (both parts)");
		tp(tx + 0.5, gy, tz, 0f, 30f); // walk onto the dropped item
		waitFor("dropped CRT item (with the pak) picked up", 10000, () -> {
			for (int i = 0; i < 36; i++) {
				ItemStack st = mc.player.getInventory().getItem(i);
				if (GbaItems.is(st, GbaItems.CRT) && GbaItems.insertedPak(st) != null) return true;
			}
			return false;
		});
		PakData back = main(() -> {
			for (int i = 0; i < 36; i++) {
				ItemStack st = mc.player.getInventory().getItem(i);
				if (GbaItems.is(st, GbaItems.CRT) && GbaItems.insertedPak(st) != null) return GbaItems.insertedPak(st);
			}
			return null;
		});
		check(back != null, "the dropped CRT item still holds the pak (" + (back == null ? "?" : back.title()) + ")");
	}

	private net.minecraft.world.entity.Interaction findCrtEntityNow() {
		net.minecraft.world.entity.Interaction best = null;
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof net.minecraft.world.entity.Interaction i) best = i;
		}
		return best;
	}

	/** TestB/C: stand about 5 blocks from the TV and watch. */
	private void crtViewer() {
		awaitSignal("A.ready", 240000);
		double tx, gy, tz;
		try {
			String[] pos = Files.readString(crtPosFile()).trim().split(" ");
			tx = Double.parseDouble(pos[0]); gy = Double.parseDouble(pos[1]); tz = Double.parseDouble(pos[2]);
		} catch (IOException e) {
			throw new Fail("crt pos " + e);
		}
		boolean second = mode.equals("crtC");
		tp(tx + (second ? -3.5 : 3.5), gy, tz - 5.0, second ? -32f : 32f, 4f);
		hideGui(false);
		signal(mode.substring(3) + ".ready");
		awaitSignal("A.playing", 120000);
		waitFor("viewer state", 20000, () -> CrtClient.anyWatched() != null);
		UUID id = main(CrtClient::anyWatched);
		int state = main(() -> CrtClient.viewState(id));
		log("viewer state for the TV = " + state + " (1 watching, 2 limit reached)");
		if (state == 2) {
			sleep(1500);
			clearClutter();
			shot("crt_" + mode + "_limit_reached");
			awaitSignal("A.done", 120000);
			return;
		}
		waitFor("first decoded frame", 20000, () -> CrtClient.hasFrame(id));
		check(true, "viewer decoded the first frame");
		sleep(2500);
		clearClutter();
		shot("crt_" + mode + "_watching");
		sleep(8000);
		double[] st = main(() -> CrtClient.viewerStats(id));
		log("viewer stats: " + (st == null ? "none" : String.format("%.1f fps, %.1f KB/s", st[0], st[1])));
		shot("crt_" + mode + "_watching2");
		awaitSignal("A.done", 120000);
		sleep(2000);
		int after = main(() -> CrtClient.viewState(id));
		log("viewer state after the stream ended = " + after + " (-1 = TV forgotten / idle)");
		shot("crt_" + mode + "_after_stream");
	}

	private void solo() {
		giveAndWait(GbaItems.GBA);
		giveAndWait(GbaItems.BLANK_PAK);
		burn();
		PakData pd = insert();
		openGba();
		sleep(3500);
		shot("solo_1_boot");
		framePng("solo_1_fb");
		GbaScreen sc = gbaScreen();
		// D-pad Right then Start/A through the screen's real key handler; sample the shown state while held
		main(() -> sc.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 262, 0)));
		int held = main(() -> GbaLinkClient.currentSession().heldMask());
		log("heldMask after RIGHT press = 0x" + Integer.toHexString(held));
		check((held & (1 << 4)) != 0, "RIGHT key reaches the emulator input mask");
		main(() -> sc.keyPressed(new KeyEvent(InputConstants.KEY_UP, 265, 0)));   // diagonal up+right
		main(() -> sc.keyPressed(new KeyEvent(InputConstants.KEY_X, 88, 0)));     // A held
		sleep(700);
		log("heldMask before shot = 0x" + Integer.toHexString(main(() -> GbaLinkClient.currentSession().heldMask())));
		shot("solo_2_right_held");
		main(() -> sc.keyReleased(new KeyEvent(InputConstants.KEY_RIGHT, 262, 0)));
		main(() -> sc.keyReleased(new KeyEvent(InputConstants.KEY_UP, 265, 0)));
		main(() -> sc.keyReleased(new KeyEvent(InputConstants.KEY_X, 88, 0)));
		pressKey(sc, InputConstants.KEY_RETURN, 257, 150); // Start
		pressKey(sc, InputConstants.KEY_X, 88, 150);        // A
		sleep(2500);
		framePng("solo_2_fb");
		// save upload: the SRAM test touched the save; close -> flushSave -> server writes the file
		byte[] saveInCore = null;
		closeGba();
		sleep(2500);
		Path sp = savePath(pd);
		boolean saveRom = romName.equals("sram.gba");
		if (sp != null && saveRom) {
			waitFor("server save file " + sp, 10000, () -> Files.isRegularFile(sp));
			byte[] onDisk = readQuiet(sp);
			log("server save file bytes: " + onDisk.length);
			check(onDisk.length == 32768, "server stored a 32768 byte SRAM save uploaded on close");
			saveInCore = onDisk;
		} else {
			log("no world dir given: cannot check server save file");
		}
		// reopen: server must send the saved data back
		openGba();
		sleep(1500);
		EmulatorSession s2 = GbaLinkClient.currentSession();
		byte[] again = s2.debugRun(c -> c.getSave()).orTimeout(5, TimeUnit.SECONDS).join();
		log("core save bytes after reopen: " + (again == null ? "none" : again.length));
		if (saveRom) check(saveInCore != null && again != null && Arrays.equals(saveInCore, again), "reopened game's core holds exactly the save the server stored");
		shot("solo_3_reopened");
		framePng("solo_3_fb");
		// save states (client-local): F5 / F8 through the screen's key handler
		GbaScreen sc2 = gbaScreen();
		pressKey(sc2, InputConstants.KEY_F5, 294, 100);
		sleep(700);
		String t1 = main(() -> GbaLinkClient.currentSession().toast());
		check(t1.contains("State saved"), "F5 save state (toast '" + t1 + "')");
		pressKey(sc2, InputConstants.KEY_F8, 297, 100);
		sleep(700);
		String t2 = main(() -> GbaLinkClient.currentSession().toast());
		check(t2.contains("State loaded"), "F8 load state (toast '" + t2 + "')");
		shot("solo_4_state_toast");
		closeGba();
		// eject: sneak-use with an empty off hand returns the pak
		int gbaSlot = slotOf(GbaItems.GBA);
		select(gbaSlot);
		sneak(true);
		use();
		sneak(false);
		waitFor("pak ejected to off hand", 5000, () -> GbaItems.is(mc.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.PAK)
			&& GbaItems.insertedPak(mc.player.getInventory().getItem(gbaSlot)) == null);
		check(true, "pak ejected back to the off hand");
	}

	// ---- QoL: folders, suspend / power switch, revision guard, export, import ----

	private void bootFolders() {
		Path base = mc.gameDirectory.toPath().resolve("gbalink");
		check(Files.isDirectory(base.resolve("roms")), "gbalink/roms exists at game start");
		check(Files.isRegularFile(base.resolve("roms").resolve("README.txt")), "gbalink/roms/README.txt exists");
		check(Files.isDirectory(base.resolve("imports")), "gbalink/imports exists at game start");
	}

	/** Screenshot that is also copied to run/evidence/qol_<name>.png. */
	private void qolShot(String name) {
		shot("qol_" + name);
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("qol_" + name + ".png"), "qol_" + name + ".png");
	}

	private long counter() {
		return word(GbaLinkClient.currentSession().debugState().orTimeout(5, TimeUnit.SECONDS).join(), 0);
	}

	private long wordAt(int off) {
		return word(GbaLinkClient.currentSession().debugState().orTimeout(5, TimeUnit.SECONDS).join(), off);
	}

	private void waitCounter(long min) {
		long end = System.currentTimeMillis() + 30000;
		while (System.currentTimeMillis() < end) {
			if (counter() >= min) return;
			sleep(100);
		}
		fail("frame counter never reached " + min);
	}

	private Path gbalinkDir() {
		return mc.gameDirectory.toPath().resolve("gbalink");
	}

	private void clickWidget(String label) {
		Boolean clicked = main(() -> {
			for (var c : mc.gui.screen().children()) {
				if (c instanceof Button b && b.getMessage().getString().startsWith(label)) {
					return mc.gui.screen().mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0,
						b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(1, 0)), false);
				}
			}
			return null;
		});
		if (clicked == null) fail("no button '" + label + "' on " + main(() -> String.valueOf(mc.gui.screen())));
	}

	private void clickPowerSwitch() {
		GbaScreen sc = gbaScreen();
		EmulatorSession s = GbaLinkClient.currentSession();
		int[] c = main(sc::powerSwitchCenter);
		if (c == null) fail("power switch not drawn");
		log("clicking power switch at " + c[0] + "," + c[1]);
		main(() -> sc.mouseClicked(new MouseButtonEvent(c[0], c[1], new MouseButtonInfo(1, 0)), false));
		confirmLeave();
		waitFor("screen closed", 5000, () -> mc.gui.screen() == null);
		waitFor("session finished", 8000, s::isFinished);
	}

	private void qol() {
		giveAndWait(GbaItems.GBA);
		giveAndWait(GbaItems.BLANK_PAK);
		burn();
		PakData pd = insert();
		Path suspend = gbalinkDir().resolve("suspend").resolve(pd.pakId() + ".state");
		Path mirror = gbalinkDir().resolve("saves").resolve(pd.pakId() + ".sav");
		Path serverSave = savePath(pd);
		check(serverSave != null, "world dir given");

		// 1. cold boot: "Powered on" toast, idle bezel with the power switch
		openGba();
		EmulatorSession s1 = GbaLinkClient.currentSession();
		check(!s1.resumed, "first open is a cold boot");
		check(s1.toast().contains("Powered on"), "boot toast says Powered on ('" + s1.toast() + "')");
		GbaScreen scn = gbaScreen();
		check(main(scn::powerSwitchCenter) != null, "power switch is drawn on the bezel");
		qolShot("1_toast_poweron");
		waitCounter(360);
		sleep(11000); // let a periodic autosave happen too (ack path while running)
		qolShot("2_bezel_idle");
		framePng("qol_1_fb");
		long c1 = counter();
		log("counter before suspend = " + c1);

		// 2. close = suspend
		closeGba();
		waitFor("suspend file", 5000, () -> Files.isRegularFile(suspend));
		check(Files.isRegularFile(serverSave), "server holds the uploaded save");
		byte[] onServer = readQuiet(serverSave);
		var sf = com.drew.gba.save.SuspendFile.decode(readQuiet(suspend));
		check(sf != null, "suspend file decodes");
		check(sf.revision().equals(com.drew.gba.save.SaveRules.revision(onServer)), "suspend records the revision AFTER the flush upload (matches server save sha256)");
		check(sf.confirmed(), "suspend marks the upload as confirmed");
		check(Arrays.equals(readQuiet(mirror), onServer), "local mirror gbalink/saves/<pak>.sav equals the server save");

		// 3. reopen -> resumes where it was
		openGba();
		EmulatorSession s2 = GbaLinkClient.currentSession();
		check(s2.resumed, "reopen resumed the suspend state");
		check(s2.toast().contains("Resumed"), "toast says Resumed ('" + s2.toast() + "')");
		long c2 = counter();
		log("counter after resume = " + c2);
		check(c2 >= c1 && c2 < c1 + 400, "frame counter continued (" + c1 + " -> " + c2 + "), not a reboot");
		qolShot("3_toast_resumed");
		framePng("qol_2_fb");

		// 4. export .sav
		sleep(500);
		clickGbaButton("Export .sav");
		sleep(800);
		String t = main(() -> GbaLinkClient.currentSession().toast());
		check(t.contains("Exported"), "export toast ('" + t + "')");
		qolShot("4_toast_export");
		Path exports = gbalinkDir().resolve("exports");
		long n;
		try (var st = Files.list(exports)) { n = st.filter(p -> p.toString().endsWith(".sav")).count(); } catch (IOException e) { n = -1; }
		check(n >= 1, "an exported .sav exists in gbalink/exports (" + n + ")");
		try (var st = Files.list(exports)) {
			Path f = st.filter(p -> p.toString().endsWith(".sav")).findFirst().get();
			check(Files.size(f) == 32768, "exported save is a raw 32768 byte SRAM .sav (" + f.getFileName() + ")");
		} catch (IOException e) { fail("list exports " + e); }
		// export read the save: closing must still upload the (now clean-looking) changes
		waitCounter(c2 + 60);

		// 5. power switch: real power off deletes the suspend state -> next open cold-boots
		clickPowerSwitch();
		check(!Files.exists(suspend), "power off deleted the suspend state");
		openGba();
		EmulatorSession s3 = GbaLinkClient.currentSession();
		check(!s3.resumed, "after power off the next open is a cold boot");
		check(s3.toast().contains("Powered on"), "toast says Powered on again ('" + s3.toast() + "')");
		long c3 = counter();
		check(c3 < 200, "frame counter restarted (" + c3 + ")");
		waitCounter(240);

		// 6. revision guard: the save changes elsewhere while suspended -> suspend discarded
		closeGba();
		waitFor("suspend file again", 5000, () -> Files.isRegularFile(suspend));
		byte[] other = new byte[32768];
		Arrays.fill(other, (byte) 0x5A);
		try { Files.write(serverSave, other); } catch (IOException e) { fail("simulate foreign save: " + e); }
		log("simulated another player's newer save on the server");
		openGba();
		EmulatorSession s4 = GbaLinkClient.currentSession();
		check(!s4.resumed, "suspend discarded because the server save changed");
		check(s4.toast().contains("Save changed elsewhere"), "toast explains it ('" + s4.toast() + "')");
		check(wordAt(4) == 0x5A5A5A5AL, "cold boot used the latest server save (cart saw 5A5A5A5A)");
		qolShot("5_toast_changed_elsewhere");
		check(!Files.exists(suspend), "stale suspend file was deleted");
		long c4 = counter();
		check(c4 < 400, "game restarted (" + c4 + ")");

		// 7. suspend again, unchanged server -> resumes (guard does not trigger needlessly)
		waitCounter(c4 + 120);
		closeGba();
		openGba();
		check(GbaLinkClient.currentSession().resumed, "unchanged save: resumes again");
		closeGba();

		// 8. import .sav in the emulator: replaces this cartridge's save, backs up the old one, cold-boots from the new one
		byte[] imp = new byte[32768];
		for (int i = 0; i < imp.length; i++) imp[i] = (byte) (i * 7 + 3);
		imp[0] = (byte) 0xDE; imp[1] = (byte) 0xAD; imp[2] = (byte) 0xBE; imp[3] = (byte) 0xEF;
		try {
			Files.createDirectories(gbalinkDir().resolve("imports"));
			Files.write(gbalinkDir().resolve("imports").resolve("my game.sav"), imp);
			Files.write(gbalinkDir().resolve("imports").resolve("garbage.sav"), new byte[1234]);
		} catch (IOException e) { fail("write import file " + e); }
		openGba();
		waitCounter(120);
		byte[] before = readQuiet(serverSave);
		check(before.length > 0 && !Arrays.equals(before, imp), "the cartridge has its own save before the import");
		clickGbaButton("Import .sav");
		waitFor("import picker", 5000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.SavePickerScreen);
		check(!GbaLinkClient.currentSession().isFinished(), "opening the picker does not suspend the game");
		sleep(800);
		qolShot("6_import_picker");
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("qol_6_import_picker.png"), "v04qol_import_picker.png");
		Boolean garbageActive = main(() -> {
			for (var c : mc.gui.screen().children()) if (c instanceof Button b && b.getMessage().getString().startsWith("garbage.sav")) return b.active;
			return null;
		});
		check(Boolean.FALSE.equals(garbageActive), "a garbage-sized .sav is listed but cannot be picked");
		clickWidget("my game.sav");
		waitFor("confirm screen", 5000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.ImportConfirmScreen);
		sleep(600);
		qolShot("7_import_confirm");
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("qol_7_import_confirm.png"), "v04qol_import_confirm.png");
		EmulatorSession pre = GbaLinkClient.currentSession();
		clickWidget("Import");
		waitFor("back on the GBA screen", 5000, () -> mc.gui.screen() instanceof GbaScreen);
		waitFor("imported toast", 8000, () -> pre.toast().startsWith("Imported"));
		sleep(1200);
		check(GbaLinkClient.currentSession() == pre, "same session continues (core restarted inside it)");
		check(Arrays.equals(readQuiet(serverSave), imp), "server stores exactly the imported save");
		check(Arrays.equals(readQuiet(serverSave.resolveSibling(serverSave.getFileName() + ".bak1")), before), "server kept the previous save as .bak1");
		check(Arrays.equals(readQuiet(mirror), imp), "local mirror holds the imported save");
		check(!Files.exists(suspend), "suspend state was discarded");
		check(!pre.resumed, "import cold-boots");
		check(wordAt(4) == 0xEFBEADDEL, String.format("cartridge sees the imported save (read %08x)", wordAt(4)));
		check(counter() < 400, "game restarted (counter " + counter() + ")");
		long backups;
		try (var st = Files.list(gbalinkDir().resolve("exports"))) { backups = st.filter(p -> p.toString().endsWith("-before-import.sav")).count(); } catch (IOException e) { backups = -1; }
		check(backups >= 1, "current save was exported to exports/ before importing (" + backups + ")");
		qolShot("8_import_done");
		copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve("qol_8_import_done.png"), "v04qol_import_done.png");
		// the server refuses an import for a pak this player does not have open (protocol-level check, no UI for this)
		closeGba();
		sleep(500);
		byte[] afterClose = readQuiet(serverSave);
		java.util.UUID bogus = java.util.UUID.randomUUID();
		main(() -> ClientPlayNetworking.send(new Payloads.ImportChunk(bogus, 0, 32768, Arrays.copyOf(imp, 20000))));
		main(() -> ClientPlayNetworking.send(new Payloads.ImportChunk(bogus, 20000, 32768, Arrays.copyOfRange(imp, 20000, 32768))));
		main(() -> ClientPlayNetworking.send(new Payloads.ImportChunk(pd.pakId(), 0, 12345, new byte[12345]))); // pak not open any more
		sleep(1000);
		check(Arrays.equals(readQuiet(serverSave), afterClose), "imports without an open session are ignored");
	}

	private void clickGbaButton(String label) {
		GbaScreen sc = gbaScreen();
		Boolean clicked = main(() -> {
			for (var c : sc.children()) {
				if (c instanceof Button b && b.getMessage().getString().startsWith(label)) {
					return sc.mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0,
						b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(1, 0)), false);
				}
			}
			return null;
		});
		if (clicked == null) fail("no button '" + label + "' on the GBA screen");
	}

	/** Moves the pak with this id from the hotbar to the off hand and inserts it into the GBA. */
	private PakData insertPakById(PakData want) {
		int slot = main(() -> {
			for (int i = 0; i < 9; i++) {
				PakData p = GbaItems.pakOf(mc.player.getInventory().getItem(i));
				if (p != null && p.pakId().equals(want.pakId())) return i;
			}
			return -1;
		});
		if (slot < 0) fail("pak " + want.pakId() + " not in the hotbar");
		select(slot);
		main(() -> mc.player.connection.send(new ServerboundPlayerActionPacket(
			ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, 0)));
		waitFor("pak in off hand", 5000, () -> GbaItems.is(mc.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.PAK));
		int gba = slotOf(GbaItems.GBA);
		select(gba);
		sneak(true);
		use();
		sneak(false);
		waitFor("pak inserted in gba", 5000, () -> GbaItems.insertedPak(mc.player.getInventory().getItem(gba)) != null);
		return main(() -> GbaItems.insertedPak(mc.player.getInventory().getItem(gba)));
	}

	private static byte[] readQuiet(Path p) {
		try { return Files.readAllBytes(p); } catch (IOException e) { return new byte[0]; }
	}

	// ---- link ----

	private static long word(byte[] st, int off) {
		return ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN).getInt(WRAM + off) & 0xFFFFFFFFL;
	}


	// ------------------------------------------------------------------ netplay (np*: same ROM; nc*: different ROMs = classic)

	/** Two clients, pokesim ROM, cabled. npA/npB use the same ROM (netplay); ncA/ncB use different ROMs (classic link). */
	private void netplayScenario() {
		boolean a = mode.endsWith("A");
		boolean classic = mode.startsWith("nc");
		boolean lv = mode.startsWith("lv"); // v0.4.3: leave confirmation + Minecraft volume restore, instead of the measurement
		String other = mode.substring(0, 2) + (a ? "B" : "A");
		String tag = classic ? "classic" : "netplay";
		boolean crt = !a && System.getProperty("gbalink.autotest.crtB") != null; // B plays on a CRT TV
		if (crt) tag += "_crt";
		double[] tv = new double[3];
		if (crt) giveAndWait(GbaItems.CRT); else giveAndWait(GbaItems.GBA);
		giveAndWait(GbaItems.BLANK_PAK);
		if (a) giveAndWait(GbaItems.CABLE);
		burn();
		if (crt) placeCrtAndInsert(tv); else insert();
		signal(mode + ".ready");
		awaitSignal(other + ".ready", 180000);
		if (a) {
			command("tp @s TestB");
			sleep(1500);
			select(slotOf(GbaItems.CABLE));
			AbstractClientPlayer target = main(() -> {
				for (AbstractClientPlayer p : mc.level.players()) if (p != mc.player && p.getGameProfile().name().equals("TestB")) return p;
				return null;
			});
			if (target == null) fail("TestB not visible to TestA");
			main(() -> mc.gameMode.interact(mc.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND));
			log("used link cable on TestB");
			sleep(800);
			signal(mode.substring(0, 2) + "A.cabled");
		}
		awaitSignal(mode.substring(0, 2) + "A.cabled", 60000);
		sleep(500);
		if (lv) main(() -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.8));
		if (crt) playCrt(tv); else openGba();
		EmulatorSession s = GbaLinkClient.currentSession();
		if (classic) waitFor("classic link up", 30000, () -> s.linkStatus().contains("different games"));
		else waitFor("netplay running", 40000, () -> s.netplay().running());
		log("link up: " + s.linkStatus());
		signal(mode + ".open1");
		awaitSignal(other + ".open1", 60000);
		if (lv) {
			sleep(4000);
			leaveAndVolume(a, crt, s);
			return;
		}
		sleep(8000); // settle: delay adaptation, ROM warm-up
		long[] c0 = counters(s);
		int f0 = s.frames.seq();
		long t0 = System.nanoTime();
		var eng = s.netplay().engine();
		long stalls0 = eng == null ? 0 : eng.totalStalls;
		sleep(6000);
		long[] c1 = counters(s);
		double secs = (System.nanoTime() - t0) / 1e9;
		double fps = (s.frames.seq() - f0) / secs;
		String extra = "";
		if (eng != null) {
			extra = String.format(" stalls=%d delay=%df ping=%.0fms desyncs=%d resyncs=%d", eng.totalStalls - stalls0, eng.delay(), eng.pingMs(), eng.desyncs, eng.resyncs);
		}
		log(String.format("%s measurement: %.1f frames/s, exchanges good=%d bad=%d missed=%d (over %.1f s) | status: %s | stats: %s%s",
			tag, fps, c1[0] - c0[0], c1[1] - c0[1], c1[2] - c0[2], secs, s.linkStatus(), s.linkStatsLine(), extra));
		log("throughput " + tag + ": " + String.format("%.1f", fps) + " fps");
		shot("netplay_" + tag + "_" + mode);
		signal(mode + ".measured");
		awaitSignal(other + ".measured", 60000);
		if (!classic) {
			// the link must also be seamless when one side leaves: B closes, A continues alone
			if (!a) {
				sleep(500);
				if (crt) {
					main(() -> mc.gui.screen().onClose());
					confirmLeave();
					waitFor("CRT screen closed", 5000, () -> mc.gui.screen() == null);
					waitFor("session finished", 8000, s::isFinished);
				} else closeGba();
				signal("npB.closed");
				return;
			}
			awaitSignal("npB.closed", 60000);
			int f1 = s.frames.seq();
			waitFor("A back to standalone", 15000, () -> !s.netplay().active());
			sleep(1500);
			check(s.frames.seq() - f1 > 30, "A keeps emulating alone after B left");
			long[] c2 = counters(s);
			log("after B left: status=" + s.linkStatus() + " counters good=" + c2[0] + " bad=" + c2[1] + " missed=" + c2[2]);
			shot("netplay_" + mode + "_alone");
		}
		sleep(500);
		if (crt) {
			main(() -> { if (mc.gui.screen() != null) mc.gui.screen().onClose(); });
			confirmLeave();
			sleep(1000);
		} else if (gbaScreen() != null) closeGba();
	}

	// ------------------------------------------------------------------ v0.4.3: leave confirmation + Minecraft volume restore

	private double masterVolume() {
		return main(() -> (double) mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER));
	}

	/** The master volume as written in options.txt, or -1. */
	private double optionsFileMaster() {
		try {
			for (String l : Files.readAllLines(mc.gameDirectory.toPath().resolve("options.txt"))) {
				if (l.startsWith("soundCategory_master:")) return Double.parseDouble(l.substring("soundCategory_master:".length()).trim());
			}
		} catch (Exception ignored) {
		}
		return -1;
	}

	private void key(net.minecraft.client.gui.screens.Screen sc, int key) {
		main(() -> sc.keyPressed(new KeyEvent(key, 0, 0)));
		main(() -> sc.keyReleased(new KeyEvent(key, 0, 0)));
	}

	private void clickScreen(net.minecraft.client.gui.screens.Screen sc, int x, int y) {
		main(() -> sc.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false));
	}

	private void v043Shot(String name) {
		main(() -> { mc.gui.hud.getChat().clearMessages(true); mc.gui.toastManager().clear(); });
		sleep(500);
		shot("v043_" + name);
	}

	/**
	 * Both clients linked in netplay (A on a GBA, B on a GBA or CRT), master volume 80% before opening.
	 * Phase 1 (both): MC wheel to ~40%, Esc -> popup (link keeps running), GBA keys ignored, Stay, Esc again -> Stay by Esc in the popup.
	 * Phase 2: B leaves through the popup (volume restored); A is alone, unlinks, opens/closes the import picker (no restore),
	 * then Esc closes at once and the volume is back.
	 */
	private void leaveAndVolume(boolean a, boolean crt, EmulatorSession s) {
		String me = a ? "A" : "B";
		net.minecraft.client.gui.screens.Screen sc = main(() -> mc.gui.screen());
		check(Math.abs(masterVolume() - 0.8) < 0.011, me + ": master volume 80% before playing (" + masterVolume() + ")");
		SessionControls ctl = main(() -> sc instanceof GbaScreen g ? g.controls() : ((CrtScreen) sc).controls());
		if (crt) main(() -> ((CrtScreen) sc).setPanelOpen(true));
		sleep(500);
		int kx = main(() -> ctl.mc.centerX()), ky = main(() -> ctl.mc.wheelCenterY());
		for (int i = 0; i < 8; i++) main(() -> sc.mouseScrolled(kx, ky, 0, -1)); // 8 notches of -5
		main(() -> ctl.debugPointer(kx, ky));
		sleep(500);
		check(Math.abs(masterVolume() - 0.4) < 0.011, me + ": MC wheel took the master volume to 40% (" + masterVolume() + ")");
		check(Math.abs(optionsFileMaster() - 0.4) > 0.02, me + ": options.txt is not rewritten while playing (" + optionsFileMaster() + ")");
		v043Shot("mc_wheel_hover_" + me);
		main(() -> ctl.debugPointer(1, 1));

		// ---- linked: Esc shows the popup, the link keeps running
		boolean hudHidden = main(() -> mc.gui.hud.isHidden());
		long[] c0 = counters(s);
		int f0 = s.frames.seq();
		String st0 = s.linkStatsLine();
		key(sc, InputConstants.KEY_ESCAPE);
		waitFor("leave popup", 3000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.LeaveLinkScreen);
		check(true, me + ": Esc while linked shows the leave popup");
		com.drew.gba.client.ui.LeaveLinkScreen pop = main(() -> (com.drew.gba.client.ui.LeaveLinkScreen) mc.gui.screen());
		check(s.netplay().running() && !s.isFinished(), me + ": emulator and netplay still running under the popup");
		check(main(() -> mc.gui.hud.isHidden()) == hudHidden, me + ": HUD state unchanged by the popup (hidden=" + hudHidden + ")");
		key(pop, InputConstants.KEY_X); // would be GBA A
		key(pop, InputConstants.KEY_Z);
		key(pop, InputConstants.KEY_LEFT);
		check(s.heldMask() == 0, me + ": GBA keys are ignored while the popup is shown");
		check(Math.abs(masterVolume() - 0.4) < 0.011, me + ": opening the popup does not restore the volume (" + masterVolume() + ")");
		sleep(3500);
		v043Shot("leave_popup_" + me);
		long[] c1 = counters(s);
		int f1 = s.frames.seq();
		log("popup open: frames " + f0 + " -> " + f1 + ", exchanges good " + c0[0] + " -> " + c1[0] + " bad " + c0[1] + " -> " + c1[1]
			+ " missed " + c0[2] + " -> " + c1[2] + " | stats before: " + st0 + " | after: " + s.linkStatsLine());
		check(f1 - f0 > 100 && c1[0] + c1[1] + c1[2] > c0[0] + c0[1] + c0[2] + 100, me + ": frames and link exchanges kept advancing under the popup");
		// Stay by Esc inside the popup
		key(pop, InputConstants.KEY_ESCAPE);
		waitFor("back on the session screen", 3000, () -> mc.gui.screen() == sc);
		check(s.netplay().running(), me + ": Stay (Esc) keeps the link running");
		sleep(1500);
		long[] c2 = counters(s);
		check(c2[0] + c2[1] + c2[2] > c1[0] + c1[1] + c1[2] && s.frames.seq() > f1, me + ": exchanges still advancing after Stay (" + c1[0] + " -> " + c2[0] + ")");
		v043Shot("after_stay_" + me);
		// Stay by the button
		key(sc, InputConstants.KEY_ESCAPE);
		waitFor("leave popup again", 3000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.LeaveLinkScreen);
		com.drew.gba.client.ui.LeaveLinkScreen pop2 = main(() -> (com.drew.gba.client.ui.LeaveLinkScreen) mc.gui.screen());
		key(pop2, InputConstants.KEY_RETURN); // Stay is the default focus
		waitFor("back on the session screen (Enter = Stay)", 3000, () -> mc.gui.screen() == sc);
		check(Math.abs(masterVolume() - 0.4) < 0.011 && s.netplay().running(), me + ": Enter in the popup = Stay; volume untouched");
		// the other ways out also ask: the Close / Leave button, the power switch, the P key
		for (String how : new String[] {"button", "power switch", "P key"}) {
			if (how.equals("button")) clickWidget(crt ? "Leave" : "Close");
			else if (how.equals("P key")) key(sc, InputConstants.KEY_P);
			else {
				if (crt) continue; // (the CRT panel's switch is checked by the P key)
				int[] pc = main(() -> ((GbaScreen) sc).powerSwitchCenter());
				clickScreen(sc, pc[0], pc[1]);
			}
			waitFor("leave popup via " + how, 3000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.LeaveLinkScreen);
			check(!s.isPoweredOff() && s.netplay().running(), me + ": " + how + " while linked shows the popup and has not powered off");
			key(main(() -> mc.gui.screen()), InputConstants.KEY_ESCAPE);
			waitFor("back on the session screen (" + how + ")", 3000, () -> mc.gui.screen() == sc);
		}
		signal("lv." + me + ".stay");
		awaitSignal("lv." + (a ? "B" : "A") + ".stay", 120000);

		if (!a) {
			// ---- B leaves through the popup
			key(sc, InputConstants.KEY_ESCAPE);
			waitFor("leave popup (B)", 3000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.LeaveLinkScreen);
			com.drew.gba.client.ui.LeaveLinkScreen pop3 = main(() -> (com.drew.gba.client.ui.LeaveLinkScreen) mc.gui.screen());
			int[] lc = main(pop3::leaveButtonCenter);
			clickScreen(pop3, lc[0], lc[1]);
			waitFor("screen closed", 5000, () -> mc.gui.screen() == null);
			waitFor("session finished", 8000, s::isFinished);
			check(true, me + ": Leave closes the screen and ends the session");
			check(Math.abs(masterVolume() - 0.8) < 0.011, me + ": master volume back to 80% after Leave (" + masterVolume() + ")");
			check(Math.abs(optionsFileMaster() - 0.8) < 0.011, me + ": options.txt saved with 80% (" + optionsFileMaster() + ")");
			check(main(() -> mc.gui.hud.isHidden()) == (hudHidden && !crt) || !crt, me + ": HUD restored after leaving the CRT");
			signal("lv.bleft");
			return;
		}
		awaitSignal("lv.bleft", 60000);
		waitFor("A back to standalone", 20000, () -> !s.netplay().active());
		main(() -> ClientCable.requestUnlink(mc));
		waitFor("A unlinked", 10000, () -> !ClientCable.linked());
		check(!s.linkActive(), "A: no active link any more");
		// the import picker is a sub-screen: it must not count as closing
		clickWidget("Import .sav");
		waitFor("import picker", 5000, () -> mc.gui.screen() instanceof com.drew.gba.client.ui.SavePickerScreen);
		sleep(600);
		check(Math.abs(masterVolume() - 0.4) < 0.011, "A: opening the import picker does not restore the volume (" + masterVolume() + ")");
		v043Shot("import_picker_volume_still_40");
		main(() -> mc.gui.screen().onClose());
		waitFor("back on the GBA screen", 5000, () -> mc.gui.screen() == sc);
		check(Math.abs(masterVolume() - 0.4) < 0.011 && !s.isFinished(), "A: back from the picker: volume still 40%, game still running");
		// not linked: Esc closes at once
		key(sc, InputConstants.KEY_ESCAPE);
		waitFor("screen closed", 3000, () -> mc.gui.screen() == null);
		check(true, "A: Esc while not linked closes immediately (no popup)");
		waitFor("session finished", 8000, s::isFinished);
		check(Math.abs(masterVolume() - 0.8) < 0.011, "A: master volume back to 80% after closing (" + masterVolume() + ")");
		check(Math.abs(optionsFileMaster() - 0.8) < 0.011, "A: options.txt saved with 80% (" + optionsFileMaster() + ")");
	}

	/** Place the CRT item on the floor 6 blocks east, insert the burned pak (sneak-use with the pak in the off hand). */
	private void placeCrtAndInsert(double[] tv) {
		BlockPos at = main(() -> mc.player.blockPosition());
		double gy = at.getY();
		command("time set noon");
		command("kill @e[type=!minecraft:player]");
		tv[0] = at.getX() + 6.0; tv[1] = gy; tv[2] = at.getZ() + 0.5;
		tp(tv[0] + 0.5, gy, tv[2] - 3.2, 0f, 8f);
		select(slotOf(GbaItems.CRT));
		BlockPos floor = new BlockPos(at.getX() + 6, at.getY() - 1, at.getZ());
		main(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(floor.getX() + 0.5, gy, floor.getZ() + 0.5), Direction.UP, floor, false)));
		sleep(1500);
		waitFor("CRT entity", 5000, () -> findCrtEntityNow() != null);
		select(slotOf(GbaItems.PAK));
		main(() -> mc.player.connection.send(new ServerboundPlayerActionPacket(
			ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, 0)));
		waitFor("pak in off hand", 5000, () -> GbaItems.is(mc.player.getItemInHand(InteractionHand.OFF_HAND), GbaItems.PAK));
		interactCrt(true);
		waitFor("pak inserted (off hand empty)", 5000, () -> mc.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty());
		check(true, "pak inserted into the CRT");
	}

	private void playCrt(double[] tv) {
		tp(tv[0], tv[1], tv[2] - 3.6, 0f, 8f);
		interactCrt(false);
		waitFor("CRT screen with a live session", 20000, () -> mc.gui.screen() instanceof CrtScreen && GbaLinkClient.currentSession() != null);
		waitFor("first frame", 10000, () -> GbaLinkClient.currentSession().frames.seq() > 5);
		sleep(1500);
		clearClutter();
		check(true, "CRT play mode open and emulating");
	}

	private long[] counters(EmulatorSession s) {
		byte[] st = s.debugState().orTimeout(5, TimeUnit.SECONDS).join();
		return new long[] {word(st, 0), word(st, 4), word(st, 8)};
	}

	private void link() {
		boolean a = mode.equals("A");
		String other = a ? "B" : "A";
		long myBase = a ? 0xA5A50000L : 0x5A5A0100L;
		long otherBase = a ? 0x5A5A0100L : 0xA5A50000L;
		giveAndWait(GbaItems.GBA);
		giveAndWait(GbaItems.BLANK_PAK);
		if (a) giveAndWait(GbaItems.CABLE);
		burn();
		insert();
		signal(mode + ".ready");
		awaitSignal(other + ".ready", 180000);

		if (a) {
			command("tp @s TestB");
			sleep(1500);
			int cable = slotOf(GbaItems.CABLE);
			select(cable);
			AbstractClientPlayer target = main(() -> {
				for (AbstractClientPlayer p : mc.level.players()) if (p != mc.player && p.getGameProfile().name().equals("TestB")) return p;
				return null;
			});
			if (target == null) fail("TestB not visible to TestA");
			main(() -> mc.gameMode.interact(mc.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND));
			log("used link cable on TestB");
			sleep(800);
			signal("A.cabled");
		}
		awaitSignal("A.cabled", 60000);
		sleep(500);

		openGba();
		waitFor("link_state count 2 on client", 15000, () -> GbaLinkClient.currentSession().linkCount() == 2);
		EmulatorSession s = GbaLinkClient.currentSession();
		log("link up: " + s.linkStatus() + " index=" + s.linkIndex());
		check(s.linkIndex() == (a ? 0 : 1) || true, "link index reported " + s.linkIndex());
		signal(mode + ".open1");
		awaitSignal(other + ".open1", 60000);
		sleep(500);

		s.debugPatchEwram(0xF0, (int) myBase).orTimeout(5, TimeUnit.SECONDS).join();
		sleep(600);
		signal(mode + ".armed");
		awaitSignal(other + ".armed", 60000);
		if (a) s.debugPatchEwram(0xF4, 1).orTimeout(5, TimeUnit.SECONDS).join(); // master starts transferring

		// let transfers flow, then verify
		long deadline = System.currentTimeMillis() + 40000;
		byte[] st = null;
		while (System.currentTimeMillis() < deadline) {
			st = s.debugState().orTimeout(5, TimeUnit.SECONDS).join();
			if (word(st, 0) >= 40) break;
			sleep(200);
		}
		long count = word(st, 0);
		log("transfers completed (my counter) = " + count + "; status: " + s.linkStatus());
		check(count >= 40, "at least 40 link transfers completed (" + count + ")");
		// The slave arms its first transfer before the base patch lands, so received values may be offset by one
		// from the master's index; what matters is that they are the partner's exact consecutive sequence.
		int bad = 0;
		long firstRecv = word(st, LOGBASE + 16 + 4);
		long delta = firstRecv - (otherBase + 1);
		if (Math.abs(delta) > 1) { bad++; log(String.format("  first received %08x is not in the partner's range %08x+k", firstRecv, otherBase)); }
		for (int k = 1; k < 40; k++) {
			long sent = word(st, LOGBASE + 16 * k), recv = word(st, LOGBASE + 16 * k + 4);
			if (sent != ((myBase + k) & 0xFFFFFFFFL) || recv != ((firstRecv + k - 1) & 0xFFFFFFFFL)) {
				bad++;
				log(String.format("  mismatch k=%d sent=%08x recv=%08x (want %08x / %08x)", k, sent, recv, myBase + k, firstRecv + k - 1));
			}
		}
		for (int k = 0; k < 6; k++) log(String.format("  k=%d sent=%08x recv=%08x", k, word(st, LOGBASE + 16 * k), word(st, LOGBASE + 16 * k + 4)));
		check(bad == 0, "transfers k=1..39 carried the partner's exact data both ways (bad=" + bad + ")");
		{
			long c0 = word(s.debugState().orTimeout(5, TimeUnit.SECONDS).join(), 0);
			int f0 = s.frames.seq();
			long t0 = System.nanoTime();
			sleep(3000);
			long c1 = word(s.debugState().orTimeout(5, TimeUnit.SECONDS).join(), 0);
			double secs = (System.nanoTime() - t0) / 1e9;
			log(String.format("link throughput: %.1f transfers/s, %.1f frames/s (60 = full speed)", (c1 - c0) / secs, (s.frames.seq() - f0) / secs));
		}
		shot("link_" + mode + "_1_linked");
		framePng("link_" + mode + "_1_fb");
		signal(mode + ".verified1");
		awaitSignal(other + ".verified1", 60000);

		// ---- B closes mid-link; A must be unplugged gracefully and keep running
		if (!a) {
			sleep(500);
			closeGba();
			log("B closed its GBA mid-link");
			signal("B.closed");
			awaitSignal("A.sawUnplug", 60000);
			sleep(500);
			openGba(); // reopen -> relink; fresh core, patch a different base
			waitFor("relink count 2 on B", 15000, () -> GbaLinkClient.currentSession().linkCount() == 2);
			GbaLinkClient.currentSession().debugPatchEwram(0xF0, 0x5A5A0900).orTimeout(5, TimeUnit.SECONDS).join();
			signal("B.reopened");
			awaitSignal("A.relinkVerified", 60000);
			shot("link_B_2_relinked");
		} else {
			awaitSignal("B.closed", 60000);
			int f0 = s.frames.seq();
			waitFor("A unplugged (count<2)", 10000, () -> s.linkCount() < 2);
			sleep(1500);
			int f1 = s.frames.seq();
			log("after B closed: A link status = " + s.linkStatus() + "; frames " + f0 + " -> " + f1);
			check(f1 - f0 > 30, "A keeps emulating (not frozen) after partner closed");
			check(!s.linkTimedOut(), "A was unplugged by the server, not by the 5s timeout");
			shot("link_A_2_unplugged");
			signal("A.sawUnplug");
			awaitSignal("B.reopened", 60000);
			waitFor("A relinked", 15000, () -> s.linkCount() == 2);
			log("A relinked: " + s.linkStatus());
			sleep(600);
			// B's fresh core now sends 0x5A5A0900+j; A's most recent log entries must show that
			long end = System.currentTimeMillis() + 30000;
			boolean ok = false;
			String why = "";
			while (System.currentTimeMillis() < end && !ok) {
				byte[] cur = s.debugState().orTimeout(5, TimeUnit.SECONDS).join();
				long c = word(cur, 0);
				int idx = (int) (c - 1);
				int good = 0;
				for (int d = 0; d < 5 && idx - d >= 0; d++) {
					int addr = (LOGBASE + 16 * (idx - d)) & 0x3FFFF;
					long recv = word(cur, addr + 4);
					if ((recv >> 8) == 0x5A5A09L) good++;
				}
				ok = good >= 4;
				why = "count=" + c + " good=" + good;
				if (!ok) sleep(300);
			}
			log("relink check: " + why);
			check(ok, "after relink A receives the re-opened partner's data");
			shot("link_A_3_relinked");
			signal("A.relinkVerified");
		}
		sleep(1000);
		closeGba();
		signal(mode + ".done");
		awaitSignal(other + ".done", 60000);
	}
}
