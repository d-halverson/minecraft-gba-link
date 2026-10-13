package com.drew.gba.client.ui;

import com.drew.gba.client.RomLibrary;
import com.drew.gba.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Lists the ROMs in the local library; picking one burns it onto the held Blank Game Pak. */
public final class RomPickerScreen extends Screen {
	private static final int PER_PAGE = 7;
	private List<RomLibrary.Rom> roms = List.of();
	private int page;

	public RomPickerScreen() {
		super(Component.literal("Burn a game onto the Game Pak"));
	}

	@Override
	protected void init() {
		roms = RomLibrary.scan();
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		int w = Math.min(300, width - 20);
		int x = (width - w) / 2;
		int y = 40;
		int pages = Math.max(1, (roms.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int i = 0; i < PER_PAGE; i++) {
			int idx = page * PER_PAGE + i;
			if (idx >= roms.size()) break;
			RomLibrary.Rom r = roms.get(idx);
			String label = r.name() + (r.code().isEmpty() ? "" : "  [" + r.code() + "]");
			addRenderableWidget(Button.builder(Component.literal(label), b -> burn(r)).bounds(x, y + i * 24, w, 20).build());
		}
		int by = y + PER_PAGE * 24 + 6;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuild(); })
			.bounds(x, by, 40, 20).build());
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuild(); })
			.bounds(x + w - 40, by, 40, 20).build());
		prev.active = page > 0;
		next.active = page < pages - 1;
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
			.bounds(x + w / 2 - 40, by, 80, 20).build());
	}

	private void burn(RomLibrary.Rom r) {
		ClientPlayNetworking.send(new Payloads.Burn(r.sha1(), r.name(), r.code()));
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		if (roms.isEmpty()) {
			g.centeredText(font, "No games found.", width / 2, 50, 0xFFFFAAAA);
			g.centeredText(font, "Put .gba files in .minecraft/gbalink/roms/ and reopen.", width / 2, 64, 0xFFCCCCCC);
		} else {
			g.centeredText(font, "Hold a Blank Game Pak, then pick a game.", width / 2, 26, 0xFFAAAAAA);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
