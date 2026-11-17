package com.watergalaxy.itemtweaker.neoforge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import com.watergalaxy.itemtweaker.ItemTweaker;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Mod("itemtweaker")
public final class ItemTweakerNeoForge {

    public ItemTweakerNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onModifyDefaultComponents);
        NeoForge.EVENT_BUS.addListener(this::onTagsUpdated);
    }

    private void onModifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        Path configDir = FMLPaths.CONFIGDIR.get();

        Map<String, ItemTweaker.ConfigOverride> overrides = ItemTweaker.loadOverrides(configDir);

        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryOps<JsonElement> registryOps = RegistryOps.create(JsonOps.INSTANCE, registryAccess);

        Set<String> matchedSelectors = new HashSet<>();
        Map<String, Integer> applyCounts = new HashMap<>();
        AtomicInteger processedItems = new AtomicInteger(0);
        AtomicBoolean summaryLogged = new AtomicBoolean(false);
        int totalItems = BuiltInRegistries.ITEM.size();

        for (Item item : BuiltInRegistries.ITEM) {
            event.modify(
                    item,
                    builder -> {
                        ItemTweaker.clearBuilderValidator(builder);
                        ItemTweaker.applyOverridesToItem(item, builder, overrides, registryOps, matchedSelectors, applyCounts);

                        if (processedItems.incrementAndGet() >= totalItems) {
                            if (summaryLogged.compareAndSet(false, true)) {
                                ItemTweaker.logApplySummary(applyCounts);
                                ItemTweaker.checkUnmatchedSelectors(overrides, matchedSelectors);
                            }
                        }
                    }
            );
        }

        ItemTweaker.LOGGER.info("ItemTweaker NeoForge registered {} configured selector(s).", overrides.size());
    }

    private void onTagsUpdated(TagsUpdatedEvent event) {
        Path configDir = FMLPaths.CONFIGDIR.get();
        ItemTweaker.generateDocsAndReferences(configDir);
    }
}