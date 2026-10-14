package com.drew.gba.client.ui;

import com.drew.gba.client.emu.EmulatorSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Replace this cartridge's save with X?" - the last step before an imported .sav is uploaded. */
public final class ImportConfirmScreen extends Screen {
	private final Screen parent;
	private final EmulatorSession session;
	private final String fileName;
	private final byte[] data;

	public ImportConfirmScreen(Screen parent, EmulatorSession session, String fileName, byte[] data) {
		super(Component.literal("Import save"));
		this.parent = parent;
		this.session = session;
		this.fileName = fileName;
		this.data = data;
	}

	/** Opens the save picker on top of {@code parent}; a valid pick leads to this confirmation; everything returns to {@code parent}. */
	public static void begin(Screen parent, EmulatorSession session) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		mc.gui.setScreen(new SavePickerScreen(parent, (name, data) ->
			mc.gui.setScreen(new ImportConfirmScreen(parent, session, name, data))));
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height / 2 + 16;
		addRenderableWidget(Button.builder(Component.literal("Import"), b -> {
			session.importSaveAsync(fileName, data);
			minecraft.gui.setScreen(parent);
		}).bounds(cx - 102, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(cx + 2, y, 100, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int y = height / 2 - 40;
		g.centeredText(font, "Replace this cartridge's save with " + fileName + "?", width / 2, y, 0xFFFFFFFF);
		g.centeredText(font, "Your current save will be exported to exports/ first.", width / 2, y + 14, 0xFFCCCCCC);
		g.centeredText(font, "The game restarts from the imported save (a cold boot).", width / 2, y + 28, 0xFFAAAAAA);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
