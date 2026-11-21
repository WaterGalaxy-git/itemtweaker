package com.watergalaxy.itemtweaker.mixin;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Redirect(
            method = "validateComponents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/component/DataComponentMap;has(Lnet/minecraft/core/component/DataComponentType;)Z",
                    ordinal = 0
            )
    )
    private static boolean itemtweaker$allowDamageableStacking(DataComponentMap components, DataComponentType<?> type) {
        return false;
    }
}