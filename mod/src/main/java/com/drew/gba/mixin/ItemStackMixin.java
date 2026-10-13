package com.drew.gba.mixin;

import com.drew.gba.GbaItems;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Anvils must not eat a copper-ingot Link Cable as repair material. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "isValidRepairItem", at = @At("HEAD"), cancellable = true)
	private void gbalink$noRepair(ItemStack repairItem, CallbackInfoReturnable<Boolean> cir) {
		if (GbaItems.isOurs(repairItem)) cir.setReturnValue(false);
	}
}
