package com.drew.gba.client.mixin;

import com.drew.gba.client.GbaLinkClient;
import com.drew.gba.net.Payloads;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands netplay inputs, hashes and snapshot chunks to the emulator on the network thread, ahead of Fabric's receiver
 * dispatch (priority 500), which would otherwise run them on the game thread at the next frame/tick. That wait is a
 * variable 0-50 ms on top of the network latency and directly raises the input delay netplay needs.
 */
@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 500)
public abstract class ClientNetplayFastPathMixin {
	@Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
	private void gbalink$fastNetplay(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof Payloads.NetplayInputS2C d) {
			GbaLinkClient.fastNetplayInput(d);
			ci.cancel();
		} else if (packet.payload() instanceof Payloads.NetplayHashS2C d) {
			GbaLinkClient.fastNetplayHash(d);
			ci.cancel();
		} else if (packet.payload() instanceof Payloads.NetplaySnapshotS2C d) {
			GbaLinkClient.fastNetplaySnapshot(d);
			ci.cancel();
		}
	}
}
