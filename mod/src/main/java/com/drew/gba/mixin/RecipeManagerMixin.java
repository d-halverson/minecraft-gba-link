package com.drew.gba.mixin;

import com.drew.gba.GbaItems;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** The "same recipe as last time" fast path calls Recipe.matches directly, bypassing RecipeMap. */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
	@Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
		at = @At("HEAD"), cancellable = true)
	private <I extends RecipeInput, T extends Recipe<I>> void gbalink$guard(RecipeType<T> type, I input, Level level,
			RecipeHolder<T> last, CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir) {
		if (GbaItems.anyOurs(input)) cir.setReturnValue(Optional.empty());
	}
}
