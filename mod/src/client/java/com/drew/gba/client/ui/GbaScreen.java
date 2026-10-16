package com.drew.gba.client.ui;

import com.drew.gba.client.emu.EmulatorSession;
import com.drew.gba.client.emu.GbaNative;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The Game Boy Advance: purple bezel around the live picture. Esc suspends; the power switch powers off.
 * A GBA game fills the screen well (240x160, 3:2). A Game Boy / Game Boy Color game (160x144, 10:9) is drawn at its own
 * aspect ratio, as large as fits the well with whole-number scaling where possible, centred, with the dark well around
 * it; a "GB" / "GBC" tag sits next to the title and the L / R shoulder buttons (which those games do not have) are hidden.
 */
public final class GbaScreen extends Screen {
	private static final Identifier TEX = Identifier.fromNamespaceAndPath("gbalink", "dynamic/screen");
	private static final int W = GbaNative.WIDTH, H = GbaNative.HEIGHT;

	private static final int A = GbaKeys.A, B = GbaKeys.B, SELECT = GbaKeys.SELECT, START = GbaKeys.START, RIGHT = GbaKeys.RIGHT,
		LEFT = GbaKeys.LEFT, UP = GbaKeys.UP, DOWN = GbaKeys.DOWN, R = GbaKeys.R, L = GbaKeys.L;

	private final EmulatorSession session; // null in message mode
	private final List<String> message;
	private boolean builtLinked;
	private final SessionControls controls; // buttons, power switch and volume wheels (shared with the CRT options panel)
	private DynamicTexture texture;
	private int texW, texH;
	private int lastSeq = -1;
	private final byte[] frameCopy = new byte[W * H * 4];
	private boolean closed;
	private boolean childOpen;

	/** Live game. */
	public GbaScreen(EmulatorSession session) {
		super(Component.literal("Game Boy Advance"));
		this.session = session;
		this.message = List.of();
		this.controls = new SessionControls(session, new SessionControls.Host() {
			@Override public Screen screen() { return GbaScreen.this; }
			@Override public void importStarting() { childOpen = true; } // the child screens return here; removed() must not suspend the game
			@Override public void powerOffNow() { session.powerOff(); closeNow(); }
			@Override public void closeNow() { GbaScreen.this.closeNow(); }
		});
	}

	/** Esc / Close: asks first while linked (see {@link SessionControls#requestLeave}). */
	@Override
	public void onClose() {
		if (controls == null) closeNow();
		else controls.requestLeave(this::closeNow);
	}

	private void closeNow() {
		super.onClose();
	}

	/** Message-only mode (missing ROM, missing native library, ...). */
	public GbaScreen(List<String> message) {
		super(Component.literal("Game Boy Advance"));
		this.session = null;
		this.message = message;
		this.controls = null;
	}

	@Override
	protected void init() {
		if (session == null) {
			addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(width / 2 - 40, height / 2 + 30, 80, 20).build());
			return;
		}
		int by = height - 24;
		int cx = width / 2;
		builtLinked = com.drew.gba.client.ClientCable.linked();
		int n = builtLinked ? 7 : 6; // an Unlink button joins the row while cabled
		int bw = Math.min(92, (width - 20 - (n - 1) * 4) / n), gap = 4;
		int x0 = cx - (n * bw + (n - 1) * gap) / 2;
		addRenderableWidget(controls.save(x0, by, bw, 18));
		addRenderableWidget(controls.load(x0 + (bw + gap), by, bw, 18));
		addRenderableWidget(controls.export(x0 + 2 * (bw + gap), by, bw, 18));
		addRenderableWidget(controls.importSav(x0 + 3 * (bw + gap), by, bw, 18));
		addRenderableWidget(controls.controls(x0 + 4 * (bw + gap), by, bw, 18));
		if (builtLinked) addRenderableWidget(controls.unlink(x0 + 5 * (bw + gap), by, bw, 18));
		addRenderableWidget(controls.leave("Close (Esc)", x0 + (n - 1) * (bw + gap), by, bw, 18));
	}

	/** Dev/test: centre of the power switch in GUI px (valid after the first render), or null. */
	public int[] powerSwitchCenter() { return controls == null ? null : controls.powerSwitchCenter(); }

	/** Dev/test: the volume knobs' controller, to drive hover / drag (null in message mode). */
	public SessionControls controls() { return controls; }

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (controls != null && controls.mouseClicked(event)) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
		if (controls != null && controls.mouseDragged(dx, dy)) return true;
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (controls != null && controls.mouseReleased()) return true;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (controls != null && controls.mouseScrolled(x, y, scrollY)) return true;
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (session == null) return super.keyPressed(event);
		if (GbaKeys.press(session, event.key(), controls::requestPowerOff)) return true;
		if (controls.volumeKey(event.key()) || controls.unlinkKey(event)) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (session == null) return super.keyReleased(event);
		if (GbaKeys.release(session, event.key())) return true;
		return super.keyReleased(event);
	}

	@Override
	public void tick() {
		if (session == null) return;
		controls.tick();
		if (com.drew.gba.client.ClientCable.linked() != builtLinked) rebuildWidgets();
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
		int footerTop = height - 24 - 24; // body plus the status and stats lines below it must end above the buttons
		int availW = width - 2 * padSide - 8, availH = footerTop - padBottom - padTop - 2;
		int dw, dh; // the screen well: the size of a GBA picture
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
		// where the picture goes: the whole well for a GBA game; a centred 10:9 rectangle for a Game Boy game
		boolean gb = session.isGameBoy();
		int px = sx, py = sy, pw = dw, ph = dh;
		if (gb) {
			int gs = Math.min(dw / GbaNative.GB_WIDTH, dh / GbaNative.GB_HEIGHT);
			if (gs >= 1) {
				pw = GbaNative.GB_WIDTH * gs;
				ph = GbaNative.GB_HEIGHT * gs;
			} else {
				pw = Math.max(10, Math.min(dw, dh * 10 / 9) / 10 * 10);
				ph = pw * 9 / 10;
			}
			px = sx + (dw - pw) / 2;
			py = sy + (dh - ph) / 2;
		}
		g.fill(px - 3, py - 3, px + pw + 3, py + ph + 3, 0xFF000000);
		int swCx = bodyL + 8 + 44 + 10 + SessionControls.SW_W / 2, swCy = bodyT + 6;
		boolean room = width / 2 - swCx - SessionControls.SW_W / 2 - 14 >= font.width("GAME BOY ADVANCE") / 2;
		if (room) g.centeredText(font, "GAME BOY ADVANCE", width / 2, bodyT + 6, 0xFFD8CCFF);
		if (gb) { // which kind of cartridge is in: "GB" or "GBC"
			String tag = "gbc".equals(session.system()) ? "GBC" : "GB";
			int tw = font.width(tag);
			int tx = room ? width / 2 + font.width("GAME BOY ADVANCE") / 2 + 8 : width / 2 - tw / 2 - 3;
			int ty = bodyT + 4;
			g.fill(tx - 3, ty - 1, tx + tw + 3, ty + 10, 0xFF1E1840);
			g.fill(tx - 2, ty, tx + tw + 2, ty + 9, 0xFF7A68D8);
			g.text(font, tag, tx, ty + 1, 0xFFFFFFFF, false);
		}
		if (!room && width / 2 - swCx < SessionControls.SW_W / 2 + 14) controls.hideSwitch(); // no space for the switch at all
		else controls.drawPowerSwitch(g, font, swCx, swCy, mouseX, mouseY, room || font.width(controls.powerLabel()) / 2 < width / 2 - swCx);

		// the live frame
		if (!session.isFinished()) {
			int seq = session.frames.seq();
			if (seq != lastSeq && seq > 0) {
				lastSeq = seq;
				int fw, fh;
				synchronized (session.frames) { // width, height and pixels of one frame together
					fw = session.frames.width();
					fh = session.frames.height();
					session.frames.copyTo(frameCopy);
				}
				if (texture == null || fw != texW || fh != texH) { // first frame, or the game's picture size changed
					if (texture != null) minecraft.getTextureManager().release(TEX);
					texture = new DynamicTexture("gbalink screen", fw, fh, true);
					texW = fw;
					texH = fh;
					minecraft.getTextureManager().register(TEX, texture);
				}
				NativeImage img = texture.getPixels();
				img.getPixelBytes().put(0, frameCopy, 0, fw * fh * 4);
				texture.upload();
			}
		}
		if (texture != null && lastSeq > 0) {
			g.blit(TEX, px, py, px + pw, py + ph, 0f, 1f, 0f, 1f);
		}

		drawControls(g, mouseX, mouseY, bodyL, bodyR, wellL, wellR, bodyT, sy, dh);

		// status line below the bezel; a toast (saved / state loaded / ...) temporarily takes its place so it
		// can never be pushed off the top of a small window
		String toast = session.toast();
		if (!toast.isEmpty() && session.error() == null) {
			g.centeredText(font, toast, width / 2, bodyB + 4, 0xFFFFFFAA);
		} else {
			String status = session.error() != null ? session.error() : session.linkStatus();
			if (session.error() == null && com.drew.gba.client.ClientCable.linked() && session.linkStatus().startsWith("Link: not connected")) {
				status = "Cable to " + com.drew.gba.client.ClientCable.partnerText() + ": " + com.drew.gba.client.ClientCable.stateLine();
			}
			g.centeredText(font, status, width / 2, bodyB + 4, session.error() != null ? 0xFFFF8888 : 0xFFD8CCFF);
			String stats = session.error() != null ? "" : session.linkStatsLine();
			if (!stats.isEmpty()) g.centeredText(font, stats, width / 2, Math.min(bodyB + 15, height - 34), 0xFF9C92C4);
			if (!session.audioAvailable() && lastSeq > 0 && lastSeq < 120) {
				g.centeredText(font, "No audio device available", width / 2, Math.max(2, bodyT - 12), 0xFFFFAAAA);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private static final int LABEL = 0xFFC9BDF2;

	private void drawControls(GuiGraphicsExtractor g, int mouseX, int mouseY, int bodyL, int bodyR, int wellL, int wellR, int bodyT, int sy, int dh) {
		int held = session.heldMask();
		int midY = sy + dh / 2;
		// D-pad: centred in the left wing (between the body edge and the screen well)
		int dx = (bodyL + wellL) / 2;
		String dir = "";
		if ((held & (1 << UP)) != 0) dir = "up";
		else if ((held & (1 << DOWN)) != 0) dir = "down";
		if ((held & (1 << LEFT)) != 0) dir = dir.isEmpty() ? "left" : dir + "_left";
		else if ((held & (1 << RIGHT)) != 0) dir = dir.isEmpty() ? "right" : dir + "_right";
		GuiSprites.sprite(g, dir.isEmpty() ? "dpad" : "dpad_" + dir, dx, midY, 40, 40);
		// A (up-right) / B (down-left): centred in the right wing, letters printed on the body below each button
		int cx = (wellR + bodyR) / 2;
		int ax = cx + 9, ay = midY - 10, bx = cx - 9, by = midY - 1;
		boolean aDown = (held & (1 << A)) != 0, bDown = (held & (1 << B)) != 0;
		GuiSprites.sprite(g, bDown ? "button_pressed" : "button", bx, by, 26, 26);
		GuiSprites.sprite(g, aDown ? "button_pressed" : "button", ax, ay, 26, 26);
		g.text(font, "A", ax - font.width("A") / 2, ay + 11, LABEL, false);
		g.text(font, "B", bx - font.width("B") / 2, by + 11, LABEL, false);
		// Start / Select pills below the screen, each with its label under it
		int ly = sy + dh + 14, mid = width / 2;
		GuiSprites.sprite(g, (held & (1 << SELECT)) != 0 ? "pill_pressed" : "pill", mid - 24, ly, 28, 12);
		GuiSprites.sprite(g, (held & (1 << START)) != 0 ? "pill_pressed" : "pill", mid + 24, ly, 28, 12);
		g.text(font, "SELECT", mid - 24 - font.width("SELECT") / 2, ly + 7, LABEL, false);
		g.text(font, "START", mid + 24 - font.width("START") / 2, ly + 7, LABEL, false);
		// shoulder buttons along the top corners of the body
		int shY = bodyT + 6;
		if (!session.isGameBoy()) { // Game Boy games have no shoulder buttons
			GuiSprites.sprite(g, (held & (1 << L)) != 0 ? "shoulder_pressed" : "shoulder", bodyL + 8 + 22, shY, 44, 10);
			GuiSprites.sprite(g, (held & (1 << R)) != 0 ? "shoulder_pressed" : "shoulder", bodyR - 8 - 22, shY, 44, 10);
		}
		// volume wheels along the bottom edge, in the wing columns under the D-pad (GBA) and the A/B buttons (Minecraft)
		int kTop = sy + dh + 7;
		controls.gba.layout(dx, kTop);
		controls.mc.layout(cx, kTop);
		controls.renderKnobs(g, font, mouseX, mouseY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		if (childOpen) { childOpen = false; return; } // only switching to the import screens
		if (closed) return;
		closed = true;
		if (controls != null) {
			controls.flush();
			controls.endSession(); // Minecraft's volume back to what it was
		}
		if (session != null) {
			session.requestStop(); // suspends in the background; the ack needs this thread, so never block on it
			if (texture != null) {
				minecraft.getTextureManager().release(TEX);
				texture = null;
			}
			com.drew.gba.client.GbaLinkClient.sessionEnded(session);
		}
	}
}
