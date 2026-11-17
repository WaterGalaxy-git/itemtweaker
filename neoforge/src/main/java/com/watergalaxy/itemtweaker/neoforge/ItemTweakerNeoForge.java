package com.watergalaxy.itemtweaker.neoforge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import com.watergalaxy.itemtweaker.ItemTweaker;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

@Mod("itemtweaker")
public class ItemTweakerNeoForge {

    public ItemTweakerNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        Path configDir = FMLPaths.CONFIGDIR.get();

        ItemTweaker.init(configDir);
        ItemTweaker.LOGGER.info("ItemTweaker NeoForge initialized.");
    }
}