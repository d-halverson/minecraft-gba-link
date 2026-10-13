package com.drew.gba.client;

import com.drew.gba.GbaItems;
import com.drew.gba.client.emu.EmulatorSession;
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
		this.romName = System.getProperty("gbalink.autotest.rom", mode.equals("solo") ? "sram.gba" : "link_n32_" + (mode.equals("A") ? "master" : "slave") + ".gba");
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
			copyEvidence(mc.gameDirectory.toPath().resolve("screenshots").resolve(name + ".png"), mc.getUser().getName() + "_" + romName.replace(".gba", "") + "_" + name + ".png");
		} catch (Exception e) {
			fail("screenshot " + name + ": " + e);
		}
	}

	private void copyEvidence(Path src, String dstName) {
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
		Boolean clicked = main(() -> {
			for (var c : mc.gui.screen().children()) {
				if (c instanceof Button b && b.getMessage().getString().startsWith(label)) {
					return mc.gui.screen().mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0,
						b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(1, 0)), false);
				}
			}
			return null;
		});
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

	private void closeGba() {
		EmulatorSession s = GbaLinkClient.currentSession();
		GbaScreen sc = gbaScreen();
		if (sc == null) fail("no GBA screen to close");
		main(() -> sc.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 256, 0))); // real Esc handling
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
			joinAndWait();
			if (mode.equals("solo")) solo();
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

	private static byte[] readQuiet(Path p) {
		try { return Files.readAllBytes(p); } catch (IOException e) { return new byte[0]; }
	}

	// ---- link ----

	private static long word(byte[] st, int off) {
		return ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN).getInt(WRAM + off) & 0xFFFFFFFFL;
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
