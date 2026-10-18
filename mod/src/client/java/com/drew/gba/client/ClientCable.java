package com.drew.gba.client;

import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is on my link cable, kept from the server's {@code cable_state} (sent even when no GBA is open), the small HUD
 * indicator that shows it, and the Unlink action (button / key / {@code /gba unlink}).
 */
public final class ClientCable {
	private static volatile List<String> members = List.of();
	private static volatile List<Boolean> open = List.of();
	private static volatile List<String> systems = List.of();
	private static volatile int myIndex = -1;
	private static volatile long selfUnlinkAt;
	/** Dev/test: pretend a cable state (names; index 0 is me) without a server. */
	private static volatile boolean debugOverride;

	private ClientCable() {}

	public static boolean linked() {
		return myIndex >= 0 && members.size() >= 2;
	}

	/** Names of the other players on the cable. */
	public static List<String> partners() {
		List<String> all = members;
		List<String> out = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) if (i != myIndex) out.add(all.get(i));
		return out;
	}

	public static String partnerText() {
		return String.join(", ", partners());
	}

	/** Receiver of the server's cable_state (game thread). */
	public static void onState(Payloads.CableState s, Minecraft mc) {
		boolean was = linked();
		String wasPartners = partnerText();
		members = List.copyOf(s.members());
		open = List.copyOf(s.open());
		systems = List.of(); // cable_systems follows right behind this packet
		myIndex = s.members().size() >= 2 ? s.myIndex() : -1;
		if (linked() && (!was || !wasPartners.equals(partnerText()))) {
			mc.gui.hud.setOverlayMessage(Component.literal("Linked to " + partnerText()), false);
		} else if (was && !linked() && System.currentTimeMillis() - selfUnlinkAt > 3000) {
			mc.gui.hud.setOverlayMessage(Component.literal("Link cable unplugged"), false);
		}
	}

	/** Receiver of the optional cable_systems packet (0.5.1): which kind of game each member has open. */
	public static void onSystems(Payloads.CableSystems s) {
		systems = List.copyOf(s.systems());
	}

	/**
	 * Why the cable cannot link me while I play a {@code mine} ("gba", "gb", "gbc") game: other members play a game of the other
	 * family (Game Boy and Game Boy Advance games cannot be linked). "" when there is nothing to say.
	 */
	public static String mismatchNote(String mine) {
		List<String> all = members, sys = systems;
		List<String> names = new ArrayList<>(), kinds = new ArrayList<>();
		for (int i = 0; i < all.size() && i < sys.size(); i++) {
			if (i == myIndex) continue;
			names.add(all.get(i));
			kinds.add(sys.get(i));
		}
		String m = com.drew.gba.server.LinkRules.mismatchMessage(mine, names, kinds);
		return m == null ? "" : m;
	}

	/** Join / disconnect: forget the cable. */
	public static void reset() {
		systems = List.of();
		members = List.of();
		open = List.of();
		myIndex = -1;
	}

	/** Dev/test only: set a fake cable (names with me at index 0). */
	public static void debugSet(List<String> names, List<Boolean> opens) {
		debugOverride = true;
		members = List.copyOf(names);
		open = List.copyOf(opens);
		myIndex = 0;
	}

	/** Sends the unlink request; shows "Unlinked from X" at once. Returns false when not linked (a toast says so). */
	public static boolean requestUnlink(Minecraft mc) {
		if (!linked()) {
			mc.gui.hud.setOverlayMessage(Component.literal("You are not linked to anyone"), false);
			return false;
		}
		String who = partnerText();
		selfUnlinkAt = System.currentTimeMillis();
		try {
			if (ClientPlayNetworking.canSend(Payloads.Unlink.TYPE)) ClientPlayNetworking.send(new Payloads.Unlink());
		} catch (Throwable ignored) {
		}
		mc.gui.hud.setOverlayMessage(Component.literal("Unlinked from " + who), false);
		if (debugOverride) reset();
		return true;
	}

	// ------------------------------------------------------------------ HUD

	private static final Identifier ICON = Identifier.fromNamespaceAndPath("gbalink", "textures/gui/icon_cable.png");

	/** The second line: what the cable is doing right now. */
	public static String stateLine() {
		EmulatorSession s = GbaLinkClient.currentSession();
		List<String> others = partners();
		if (s == null || s.isFinished()) {
			List<String> playing = new ArrayList<>();
			for (int i = 0; i < members.size(); i++) if (i != myIndex && i < open.size() && open.get(i)) playing.add(members.get(i));
			return playing.isEmpty() ? "open your GBA to join" : String.join(", ", playing) + (playing.size() == 1 ? " has" : " have") + " a GBA open - open yours";
		}
		String st = s.linkHudState();
		if (!st.isEmpty()) return st;
		List<String> waiting = new ArrayList<>();
		for (int i = 0; i < members.size(); i++) if (i != myIndex && !(i < open.size() && open.get(i))) waiting.add(members.get(i));
		if (!waiting.isEmpty()) return "waiting for " + String.join(", ", waiting) + " to open their GBA";
		return others.isEmpty() ? "" : "connecting…";
	}

	/** Registered as a HUD element: top-right (below status effects), or top-centre while the F3 screen is up. */
	public static void render(GuiGraphicsExtractor g, DeltaTracker dt) {
		if (!linked()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden() || mc.player == null) return;
		Font font = mc.font;
		String l1 = "Linked to " + partnerText();
		String l2 = stateLine();
		int w = Math.max(font.width(l1) + 16, l2.isEmpty() ? 0 : font.width(l2)) + 10, h = l2.isEmpty() ? 16 : 26;
		int x, y;
		if (mc.getDebugOverlay().showDebugScreen()) {
			x = (g.guiWidth() - w) / 2;
			y = 3;
		} else {
			x = g.guiWidth() - w - 4;
			y = 4 + (mc.player.getActiveEffects().isEmpty() ? 0 : 54); // below the potion effect icons
		}
		g.fill(x, y, x + w, y + h, 0x90101020);
		g.fill(x, y, x + 2, y + h, 0xFF7A68D8); // indigo accent
		g.blit(RenderPipelines.GUI_TEXTURED, ICON, x + 5, y + 3, 0f, 0f, 12, 10, 48, 40, 48, 40);
		g.text(font, l1, x + 20, y + 4, 0xFFE4DCFF, true);
		if (!l2.isEmpty()) g.text(font, l2, x + 6, y + 15, 0xFFB8ADE0, true);
	}
}
