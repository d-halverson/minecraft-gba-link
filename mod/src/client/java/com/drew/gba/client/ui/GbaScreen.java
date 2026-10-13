package com.drew.gba.client.ui;

import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.emu.GbaNative;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/** The Game Boy Advance: purple bezel around a 240x160 live texture. Esc closes (and saves). */
public final class GbaScreen extends Screen {
	private static final Identifier TEX = Identifier.fromNamespaceAndPath("gbalink", "dynamic/screen");
	private static final int W = GbaNative.WIDTH, H = GbaNative.HEIGHT;

	// GBA KEYINPUT bit order
	private static final int A = 0, B = 1, SELECT = 2, START = 3, RIGHT = 4, LEFT = 5, UP = 6, DOWN = 7, R = 8, L = 9;

	private final EmulatorSession session; // null in message mode
	private final List<String> message;
	private DynamicTexture texture;
	private int lastSeq = -1;
	private final byte[] frameCopy = new byte[W * H * 4];
	private boolean closed;

	/** Live game. */
	public GbaScreen(EmulatorSession session) {
		super(Component.literal("Game Boy Advance"));
		this.session = session;
		this.message = List.of();
	}

	/** Message-only mode (missing ROM, missing native library, ...). */
	public GbaScreen(List<String> message) {
		super(Component.literal("Game Boy Advance"));
		this.session = null;
		this.message = message;
	}

	private static int bitFor(int scancode) {
		return switch (scancode) {
			case InputConstants.KEY_RIGHT -> RIGHT;
			case InputConstants.KEY_LEFT -> LEFT;
			case InputConstants.KEY_UP -> UP;
			case InputConstants.KEY_DOWN -> DOWN;
			case InputConstants.KEY_X -> A;
			case InputConstants.KEY_Z -> B;
			case InputConstants.KEY_A -> L;
			case InputConstants.KEY_S -> R;
			case InputConstants.KEY_RETURN, 88 /* keypad enter */ -> START;
			case InputConstants.KEY_BACKSPACE -> SELECT;
			default -> -1;
		};
	}

	@Override
	protected void init() {
		if (session == null) {
			addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(width / 2 - 40, height / 2 + 30, 80, 20).build());
			return;
		}
		if (texture == null) {
			texture = new DynamicTexture("gbalink screen", W, H, true);
			minecraft.getTextureManager().register(TEX, texture);
		}
		int by = height - 24;
		int cx = width / 2;
		addRenderableWidget(Button.builder(Component.literal("Save State (F5)"), b -> session.saveStateAsync())
			.bounds(cx - 155, by, 100, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Load State (F8)"), b -> session.loadStateAsync())
			.bounds(cx - 50, by, 100, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close (Esc)"), b -> onClose())
			.bounds(cx + 55, by, 100, 18).build());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (session == null) return super.keyPressed(event);
		int sc = event.key();
		int bit = bitFor(sc);
		if (bit >= 0) {
			session.press(bit);
			return true;
		}
		if (sc == InputConstants.KEY_F5) { session.saveStateAsync(); return true; }
		if (sc == InputConstants.KEY_F8) { session.loadStateAsync(); return true; }
		if (sc == 43 /* tab */) { session.turbo = true; return true; }
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (session == null) return super.keyReleased(event);
		int sc = event.key();
		int bit = bitFor(sc);
		if (bit >= 0) { session.release(bit); return true; }
		if (sc == 43) { session.turbo = false; return true; }
		return super.keyReleased(event);
	}

	@Override
	public void tick() {
		if (session == null) return;
		session.setVolume(minecraft.options.getSoundSourceVolume(SoundSource.MASTER));
		if (!minecraft.isWindowActive() && System.getProperty("gbalink.autotest") == null) { // (dev autotest runs unfocused)
			session.releaseAll();
			session.turbo = false;
		}
		if (session.isFinished() && session.error() != null) {
			// emulator died: stay open so the player can read the error
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xE0080810);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (session == null) {
			int y = height / 2 - 10 - message.size() * 6;
			for (String line : message) {
				g.centeredText(font, line, width / 2, y, 0xFFFFFFFF);
				y += 12;
			}
			super.extractRenderState(g, mouseX, mouseY, a);
			return;
		}

		// Layout. The wings hold the controls: margin | control cluster | margin, measured from the screen well.
		final int wellPad = 8, margin = 8, clusterW = 34, wing = margin + clusterW + margin;
		int padSide = wellPad + wing, padTop = 26, padBottom = 34;
		int footerTop = height - 24 - 14; // body (plus the status line below it) must end above the buttons
		int availW = width - 2 * padSide - 8, availH = footerTop - padBottom - padTop - 2;
		int dw, dh;
		int sInt = Math.min(availW / W, availH / H);
		if (sInt >= 1) {
			dw = W * sInt;
			dh = H * sInt;
		} else { // window/GUI scale too small for 1:1: shrink the image (3:2) rather than overlap anything
			dw = Math.max(90, Math.min(availW, availH * 3 / 2) / 3 * 3);
			dh = dw * 2 / 3;
		}
		int sx = (width - dw) / 2;
		int sy = Math.max(padTop + 2, Math.min((height - dh) / 2 - 8, footerTop - padBottom - dh));
		int bodyL = sx - padSide, bodyR = sx + dw + padSide, bodyT = sy - padTop, bodyB = sy + dh + padBottom;
		int wellL = sx - wellPad, wellR = sx + dw + wellPad;
		// body
		g.fill(bodyL - 2, bodyT - 2, bodyR + 2, bodyB + 2, 0xFF1E1840);
		g.fill(bodyL, bodyT, bodyR, bodyB, 0xFF4B3C94);
		g.fill(bodyL + 2, bodyT + 2, bodyR - 2, bodyT + 4, 0xFF7466BD);
		g.fill(bodyL + 2, bodyB - 4, bodyR - 2, bodyB - 2, 0xFF352A70);
		// screen well
		g.fill(wellL, sy - wellPad, wellR, sy + dh + wellPad, 0xFF1A1830);
		g.fill(sx - 3, sy - 3, sx + dw + 3, sy + dh + 3, 0xFF000000);
		g.centeredText(font, "GAME BOY ADVANCE", width / 2, bodyT + 6, 0xFFD8CCFF);

		// the live frame
		if (texture != null && !session.isFinished()) {
			int seq = session.frames.seq();
			if (seq != lastSeq && seq > 0) {
				lastSeq = seq;
				session.frames.copyTo(frameCopy);
				NativeImage img = texture.getPixels();
				img.getPixelBytes().put(0, frameCopy, 0, frameCopy.length);
				texture.upload();
			}
		}
		if (texture != null && lastSeq > 0) {
			g.blit(TEX, sx, sy, sx + dw, sy + dh, 0f, 1f, 0f, 1f);
		}

		drawControls(g, bodyL, bodyR, wellL, wellR, bodyT, sy, dh);

		// status line below the bezel; a toast (saved / state loaded / ...) temporarily takes its place so it
		// can never be pushed off the top of a small window
		String toast = session.toast();
		if (!toast.isEmpty() && session.error() == null) {
			g.centeredText(font, toast, width / 2, bodyB + 4, 0xFFFFFFAA);
		} else {
			String status = session.error() != null ? session.error() : session.linkStatus();
			g.centeredText(font, status, width / 2, bodyB + 4, session.error() != null ? 0xFFFF8888 : 0xFFD8CCFF);
			if (!session.audioAvailable() && lastSeq > 0 && lastSeq < 120) {
				g.centeredText(font, "No audio device available", width / 2, Math.max(2, bodyT - 12), 0xFFFFAAAA);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private static final int LABEL = 0xFFC9BDF2;

	private static Identifier gui(String name) {
		return Identifier.fromNamespaceAndPath("gbalink", "textures/gui/" + name + ".png");
	}

	/** Blits a 4x supersampled sprite (4*gw x 4*gh texels) at gw x gh GUI px, centred on (cx, cy), alpha blended. */
	private static void sprite(GuiGraphicsExtractor g, String name, int cx, int cy, int gw, int gh) {
		int tw = gw * 4, th = gh * 4;
		g.blit(RenderPipelines.GUI_TEXTURED, gui(name), cx - gw / 2, cy - gh / 2, 0f, 0f, gw, gh, tw, th, tw, th);
	}

	private void drawControls(GuiGraphicsExtractor g, int bodyL, int bodyR, int wellL, int wellR, int bodyT, int sy, int dh) {
		int held = session.heldMask();
		int midY = sy + dh / 2;
		// D-pad: centred in the left wing (between the body edge and the screen well)
		int dx = (bodyL + wellL) / 2;
		String dir = "";
		if ((held & (1 << UP)) != 0) dir = "up";
		else if ((held & (1 << DOWN)) != 0) dir = "down";
		if ((held & (1 << LEFT)) != 0) dir = dir.isEmpty() ? "left" : dir + "_left";
		else if ((held & (1 << RIGHT)) != 0) dir = dir.isEmpty() ? "right" : dir + "_right";
		sprite(g, dir.isEmpty() ? "dpad" : "dpad_" + dir, dx, midY, 40, 40);
		// A (up-right) / B (down-left): centred in the right wing, letters printed on the body below each button
		int cx = (wellR + bodyR) / 2;
		int ax = cx + 9, ay = midY - 10, bx = cx - 9, by = midY - 1;
		boolean aDown = (held & (1 << A)) != 0, bDown = (held & (1 << B)) != 0;
		sprite(g, bDown ? "button_pressed" : "button", bx, by, 26, 26);
		sprite(g, aDown ? "button_pressed" : "button", ax, ay, 26, 26);
		g.text(font, "A", ax - font.width("A") / 2, ay + 11, LABEL, false);
		g.text(font, "B", bx - font.width("B") / 2, by + 11, LABEL, false);
		// Start / Select pills below the screen, each with its label under it
		int ly = sy + dh + 14, mid = width / 2;
		sprite(g, (held & (1 << SELECT)) != 0 ? "pill_pressed" : "pill", mid - 24, ly, 28, 12);
		sprite(g, (held & (1 << START)) != 0 ? "pill_pressed" : "pill", mid + 24, ly, 28, 12);
		g.text(font, "SELECT", mid - 24 - font.width("SELECT") / 2, ly + 7, LABEL, false);
		g.text(font, "START", mid + 24 - font.width("START") / 2, ly + 7, LABEL, false);
		// shoulder buttons along the top corners of the body
		int shY = bodyT + 6;
		sprite(g, (held & (1 << L)) != 0 ? "shoulder_pressed" : "shoulder", bodyL + 8 + 22, shY, 44, 10);
		sprite(g, (held & (1 << R)) != 0 ? "shoulder_pressed" : "shoulder", bodyR - 8 - 22, shY, 44, 10);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		if (closed) return;
		closed = true;
		if (session != null) {
			session.stopAndWait(1500);
			if (texture != null) {
				minecraft.getTextureManager().release(TEX);
				texture = null;
			}
			com.drew.gba.client.GbaLinkClient.sessionEnded(session);
		}
	}
}
