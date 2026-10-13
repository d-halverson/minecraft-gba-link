package com.drew.gba;

import com.drew.gba.net.Payloads;
import com.drew.gba.server.GbaServer;
import net.fabricmc.api.ModInitializer;

/**
 * GBA Link: playable Game Boy Advance items. Nothing is registered with the game registries, so vanilla
 * clients can connect; everything rides on custom payloads and item components.
 */
public class GbaLink implements ModInitializer {
	public static final String MOD_ID = "gbalink";

	@Override
	public void onInitialize() {
		Payloads.register();
		GbaServer.init();
		com.drew.gba.server.SelfTest.init();
	}
}
