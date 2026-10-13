package com.drew.gba.crt;

/**
 * Where things are on the placed CRT model. Model coordinates are in 1/16 block units exactly as written in
 * assets/gbalink/models/item/crt_placed.json. The item_display is centred on (8,8,8) and the renderer turns the
 * item 180 degrees about Y, then applies the entity yaw, so a TV with yaw Y has its front facing (-sin Y, cos Y).
 */
public final class CrtGeometry {
	private CrtGeometry() {}

	/** The 3:2 picture rectangle in the middle of the 4:3 tube, in model units (front of the TV is towards smaller z). */
	public static final double SCREEN_X0 = -10.8, SCREEN_X1 = 10.8, SCREEN_Y0 = 14.525, SCREEN_Y1 = 28.925, SCREEN_Z = 1.6;
	/** Hitbox of each of the two interaction entities (one per cell) in blocks. */
	public static final float HIT_WIDTH = 1.0f, HIT_HEIGHT = 2.0f;

	/** Yaw (degrees, vanilla convention) a TV needs so that its front faces a player looking along {@code playerYaw}. */
	public static float yawFacingPlayer(float playerYaw) {
		float snapped = Math.round(playerYaw / 90f) * 90f;
		float y = snapped + 180f;
		y = ((y % 360f) + 360f) % 360f;
		return y;
	}

	/**
	 * World position of a model point. {@code bx,by,bz} is the TV's base (bottom centre).
	 * @return {x, y, z}
	 */
	public static double[] worldPoint(double bx, double by, double bz, double yawDeg, double mx, double my, double mz) {
		double dx = -((mx - 8) / 16.0), dy = (my - 8) / 16.0, dz = -((mz - 8) / 16.0); // renderer's 180 degree flip
		double th = Math.toRadians(-yawDeg);
		double c = Math.cos(th), s = Math.sin(th);
		double x = dx * c + dz * s, z = -dx * s + dz * c;
		return new double[] {bx + x, by + 0.5 + dy, bz + z};
	}

	/** The four screen corners in world space: top-left, bottom-left, bottom-right, top-right as seen from the front. */
	public static double[][] screenCorners(double bx, double by, double bz, double yawDeg) {
		return new double[][] {
			worldPoint(bx, by, bz, yawDeg, SCREEN_X1, SCREEN_Y1, SCREEN_Z),
			worldPoint(bx, by, bz, yawDeg, SCREEN_X1, SCREEN_Y0, SCREEN_Z),
			worldPoint(bx, by, bz, yawDeg, SCREEN_X0, SCREEN_Y0, SCREEN_Z),
			worldPoint(bx, by, bz, yawDeg, SCREEN_X0, SCREEN_Y1, SCREEN_Z)};
	}

	public static double[] screenCenter(double bx, double by, double bz, double yawDeg) {
		return worldPoint(bx, by, bz, yawDeg, (SCREEN_X0 + SCREEN_X1) / 2, (SCREEN_Y0 + SCREEN_Y1) / 2, SCREEN_Z);
	}
}
