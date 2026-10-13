package com.drew.gba.mixin;

import com.drew.gba.net.Payloads;
import com.drew.gba.server.GbaServer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relays link_data on the Netty thread, ahead of Fabric's receiver dispatch (priority 500 = earlier than the
 * default 1000), which would otherwise wait for the next server tick (50 ms) before running our handler.
 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 500)
public abstract class LinkDataFastPathMixin {
	@Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
	private void gbalink$fastLinkData(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof Payloads.LinkDataC2S d) {
			GbaServer.fastRelay((ServerGamePacketListenerImpl) (Object) this, d);
			ci.cancel();
		} else if (packet.payload() instanceof Payloads.CrtStreamC2S s) {
			com.drew.gba.server.CrtServer.fastStream((ServerGamePacketListenerImpl) (Object) this, s);
			ci.cancel();
		}
	}
}
