package com.drew.gba.mixin;

import com.drew.gba.GbaItems;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/** No vanilla recipe (crafting, crafter, furnaces, smithing, ...) may match an input holding a gbalink item. */
@Mixin(RecipeMap.class)
public abstract class RecipeMapMixin {
	@Inject(method = "getRecipesFor", at = @At("HEAD"), cancellable = true)
	private <I extends RecipeInput, T extends Recipe<I>> void gbalink$guard(RecipeType<T> type, I input, Level level,
			CallbackInfoReturnable<Stream<RecipeHolder<T>>> cir) {
		if (GbaItems.anyOurs(input)) cir.setReturnValue(Stream.empty());
	}
}
