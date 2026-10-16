package com.drew.gba.client.ui;

import com.drew.gba.client.RomLibrary;
import com.drew.gba.client.GbaLinkClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Lists the ROMs in the local library; picking one burns it onto the held Blank Game Pak. */
public final class RomPickerScreen extends Screen {
	private int perPage = 7;
	private List<RomLibrary.Rom> roms = List.of();
	private List<RomLibrary.Rejected> skipped = List.of();
	private int page;

	public RomPickerScreen() {
		super(Component.literal("Burn a game onto the Game Pak"));
	}

	@Override
	protected void init() {
		roms = RomLibrary.scan();
		skipped = RomLibrary.lastRejected();
		perPage = Math.max(2, Math.min(7, (height - 40 - 78 - (skipped.isEmpty() ? 0 : 36)) / 24));
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		int w = Math.min(300, width - 20);
		int x = (width - w) / 2;
		int y = 40;
		int pages = Math.max(1, (roms.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int i = 0; i < perPage; i++) {
			int idx = page * perPage + i;
			if (idx >= roms.size()) break;
			RomLibrary.Rom r = roms.get(idx);
			String label = r.name() + "  [" + r.tag() + "]" + (r.code().isEmpty() ? "" : "  " + r.code()) + "  #" + com.drew.gba.client.GbaLinkClient.romId(r.sha1());
			if (!r.warning().isEmpty()) label = r.name() + "  [" + r.tag() + "]  (incomplete file!)  #" + com.drew.gba.client.GbaLinkClient.romId(r.sha1());
			Button rb = addRenderableWidget(Button.builder(Component.literal(label), b -> burn(r)).bounds(x, y + i * 24, w, 20).build());
			if (!r.warning().isEmpty()) rb.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(r.warning())));
			if (r.gameBoy() && !GbaLinkClient.gameBoySupported()) {
				rb.active = false; // the emulator library is older than the mod (swapped files): cannot play Game Boy games
				rb.setMessage(Component.literal(r.name() + "  [" + r.tag() + "]  needs a mod update"));
			}
		}
		int by = y + perPage * 24 + 6;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuild(); })
			.bounds(x, by, 40, 20).build());
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuild(); })
			.bounds(x + w - 40, by, 40, 20).build());
		prev.active = page > 0;
		next.active = page < pages - 1;
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
			.bounds(x + w / 2 - 40, by, 80, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Open ROMs folder"), b -> RomLibrary.openFolder(RomLibrary.romsDir()))
			.bounds(x, by + 24, w, 20).build());
	}

	private void burn(RomLibrary.Rom r) {
		GbaLinkClient.burn(r);
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		if (roms.isEmpty()) {
			g.centeredText(font, "No games found.", width / 2, 50, 0xFFFFAAAA);
			g.centeredText(font, "Put .gba, .gb or .gbc files in .minecraft/gbalink/roms/ and reopen.", width / 2, 64, 0xFFCCCCCC);
			g.centeredText(font, "Click \"Open ROMs folder\" below to find it.", width / 2, 78, 0xFFCCCCCC);
		} else {
			g.centeredText(font, "Hold a Blank Game Pak, then pick a game.", width / 2, 26, 0xFFAAAAAA);
		}
		java.util.List<String> warns = new java.util.ArrayList<>();
		for (RomLibrary.Rom r : roms) if (!r.warning().isEmpty()) warns.add(r.name() + ": " + r.warning());
		if (!warns.isEmpty()) { // ROMs that are listed but look broken (a cut-off dump)
			String t = warns.get(0);
			if (font.width(t) > width - 20) t = font.plainSubstrByWidth(t, width - 30) + "...";
			g.centeredText(font, t, width / 2, height - 12, 0xFFFFCC66);
		}
		if (!skipped.isEmpty()) { // files that are not valid ROMs, with the reason (up to three lines)
			int w = Math.min(300, width - 20);
			int ly = 40 + perPage * 24 + 6 + 24 + 22;
			g.centeredText(font, skipped.size() + (skipped.size() == 1 ? " file was skipped:" : " files were skipped:"), width / 2, ly, 0xFFFFAA88);
			for (int i = 0; i < Math.min(2, skipped.size()); i++) {
				String t = skipped.get(i).file() + " - " + skipped.get(i).reason();
				if (font.width(t) > w + 40) t = font.plainSubstrByWidth(t, w + 30) + "...";
				g.centeredText(font, t, width / 2, ly + 11 + i * 10, 0xFFCC9977);
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
