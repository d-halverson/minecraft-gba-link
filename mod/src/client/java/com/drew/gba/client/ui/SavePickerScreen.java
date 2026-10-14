package com.drew.gba.client.ui;

import com.drew.gba.client.RomLibrary;
import com.drew.gba.save.SaveRules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Lists the .sav files in {@code gbalink/imports/}; picking a valid one hands (name, bytes) to the caller, which shows the next screen. */
public final class SavePickerScreen extends Screen {
	private record Entry(Path path, long size, String problem) {}

	private final Screen parent;
	private final BiConsumer<String, byte[]> onPick;
	private List<Entry> entries = List.of();
	private int page;
	private int perPage = 6;
	private String error = "";

	public SavePickerScreen(Screen parent, BiConsumer<String, byte[]> onPick) {
		super(Component.literal("Import a save file"));
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		List<Entry> list = new ArrayList<>();
		try (Stream<Path> s = Files.list(RomLibrary.importsDir())) {
			s.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sav"))
				.sorted()
				.forEach(p -> {
					try {
						long size = Files.size(p);
						list.add(new Entry(p, size, SaveRules.validateImportSize(size)));
					} catch (IOException ignored) {
					}
				});
		} catch (IOException ignored) {
		}
		entries = list;
		perPage = Math.max(2, Math.min(6, (height - 40 - 56) / 24));
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		int w = Math.min(300, width - 20);
		int x = (width - w) / 2;
		int y = 40;
		int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int i = 0; i < perPage; i++) {
			int idx = page * perPage + i;
			if (idx >= entries.size()) break;
			Entry e = entries.get(idx);
			String label = e.path().getFileName() + "  (" + e.size() + " B)" + (e.problem() == null ? "" : "  - not a GBA save");
			Button b = addRenderableWidget(Button.builder(Component.literal(label), bt -> pick(e)).bounds(x, y + i * 24, w, 20).build());
			b.active = e.problem() == null;
		}
		int by = y + perPage * 24 + 6;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuild(); }).bounds(x, by, 30, 20).build());
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuild(); }).bounds(x + w - 30, by, 30, 20).build());
		prev.active = page > 0;
		next.active = page < pages - 1;
		addRenderableWidget(Button.builder(Component.literal("Open imports folder"), b -> RomLibrary.openFolder(RomLibrary.importsDir()))
			.bounds(x + 34, by, w / 2 + 6, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
			.bounds(x + w / 2 + 44, by, w / 2 - 78, 20).build());
	}

	private void pick(Entry e) {
		try {
			byte[] data = Files.readAllBytes(e.path());
			String why = SaveRules.validateImportSize(data.length);
			if (why != null) {
				error = why;
				return;
			}
			onPick.accept(e.path().getFileName().toString(), data);
		} catch (IOException ex) {
			error = "Could not read the file: " + ex.getMessage();
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		g.centeredText(font, "Files come from .minecraft/gbalink/imports/ (.sav, 512 B to 128 KiB)", width / 2, 26, 0xFFAAAAAA);
		if (entries.isEmpty()) {
			g.centeredText(font, "No .sav files yet. Click \"Open imports folder\", copy one in, then reopen.", width / 2, 50, 0xFFFFAAAA);
		}
		if (!error.isEmpty()) g.centeredText(font, error, width / 2, height - 14, 0xFFFF8888);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
