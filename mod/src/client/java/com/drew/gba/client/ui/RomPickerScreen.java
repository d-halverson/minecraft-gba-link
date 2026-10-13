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
	private String importName;
	private byte[] importBytes;
	private String notice = "";
	private List<RomLibrary.Rom> roms = List.of();
	private int page;

	public RomPickerScreen() {
		super(Component.literal("Burn a game onto the Game Pak"));
	}

	@Override
	protected void init() {
		roms = RomLibrary.scan();
		perPage = Math.max(2, Math.min(7, (height - 40 - 78) / 24));
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
			String label = r.name() + (r.code().isEmpty() ? "" : "  [" + r.code() + "]");
			addRenderableWidget(Button.builder(Component.literal(label), b -> burn(r)).bounds(x, y + i * 24, w, 20).build());
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
			.bounds(x, by + 24, w / 2 - 2, 20).build());
		String imp = importName == null ? "Burn with existing save..." : "Save: " + importName + " (change)";
		addRenderableWidget(Button.builder(Component.literal(imp), b -> minecraft.gui.setScreen(new SavePickerScreen(this, (n, d) -> {
			importName = n;
			importBytes = d;
		}))).bounds(x + w / 2 + 2, by + 24, w - w / 2 - 2, 20).build());
	}

	private void burn(RomLibrary.Rom r) {
		if (importBytes != null && !GbaLinkClient.serverSupportsImport()) {
			notice = "This server's GBA Link mod can't import saves - ask the admin to update it";
			return;
		}
		GbaLinkClient.burn(r, importBytes);
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		if (roms.isEmpty()) {
			g.centeredText(font, "No games found.", width / 2, 50, 0xFFFFAAAA);
			g.centeredText(font, "Put .gba files in .minecraft/gbalink/roms/ and reopen.", width / 2, 64, 0xFFCCCCCC);
			g.centeredText(font, "Click \"Open ROMs folder\" below to find it.", width / 2, 78, 0xFFCCCCCC);
		} else {
			g.centeredText(font, importName == null ? "Hold a Blank Game Pak, then pick a game."
				: "Hold a Blank Game Pak, then pick a game. It will start with " + importName + ".", width / 2, 26, 0xFFAAAAAA);
		}
		if (!notice.isEmpty()) g.centeredText(font, notice, width / 2, height - 14, 0xFFFF8888);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
