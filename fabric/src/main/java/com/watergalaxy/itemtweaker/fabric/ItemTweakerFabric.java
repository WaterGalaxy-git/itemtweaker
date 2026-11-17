package com.watergalaxy.itemtweaker.fabric;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.watergalaxy.itemtweaker.ItemTweaker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ItemTweakerFabric implements ModInitializer{

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();

        var overrides = ItemTweaker.loadOverrides(configDir);

        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryOps<JsonElement> registryOps = RegistryOps.create(JsonOps.INSTANCE, registryAccess);

        DefaultItemComponentEvents.MODIFY.register(context -> {
            Set<String> matchedSelectors = new HashSet<>();
            Map<String, Integer> applyCounts = new HashMap<>();

            context.modify(item -> true, (builder, item) -> ItemTweaker.applyOverridesToItem(item, builder, overrides, registryOps, matchedSelectors, applyCounts));

            ItemTweaker.logApplySummary(applyCounts);
            ItemTweaker.checkUnmatchedSelectors(overrides, matchedSelectors);
            ItemTweaker.generateDocsAndReferences(configDir);
        });

        ItemTweaker.LOGGER.info("ItemTweaker loaded {} configured selector(s)", overrides.size());
    }
}