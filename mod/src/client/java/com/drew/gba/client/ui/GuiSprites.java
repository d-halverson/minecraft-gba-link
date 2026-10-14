package com.drew.gba.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The 4x supersampled control sprites from tools/gen_gui_sprites.py (textures/gui/*.png). */
final class GuiSprites {
	private GuiSprites() {}

	static Identifier gui(String name) {
		return Identifier.fromNamespaceAndPath("gbalink", "textures/gui/" + name + ".png");
	}

	/** Blits a 4x supersampled sprite (4*gw x 4*gh texels) at gw x gh GUI px, centred on (cx, cy), alpha blended. */
	static void sprite(GuiGraphicsExtractor g, String name, int cx, int cy, int gw, int gh) {
		int tw = gw * 4, th = gh * 4;
		g.blit(RenderPipelines.GUI_TEXTURED, gui(name), cx - gw / 2, cy - gh / 2, 0f, 0f, gw, gh, tw, th, tw, th);
	}
}
