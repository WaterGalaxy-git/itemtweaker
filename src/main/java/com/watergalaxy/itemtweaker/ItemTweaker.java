package com.watergalaxy.itemtweaker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ItemTweaker implements ModInitializer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("ItemTweaker");

    private record ConfigOverride(
            JsonObject components,
            List<String> exclusions
    ) {}

    @Override
    public void onInitialize() {

        Map<String, ConfigOverride> overrides = loadOverrides();

        RegistryAccess registryAccess =
                RegistryAccess.fromRegistryOfRegistries(
                        BuiltInRegistries.REGISTRY
                );

        RegistryOps<JsonElement> registryOps =
                RegistryOps.create(JsonOps.INSTANCE, registryAccess);

        DefaultItemComponentEvents.MODIFY.register(context -> {

            Set<String> matchedSelectors = new HashSet<>();
            Map<String, Integer> applyCounts = new HashMap<>();

            context.modify(

                    item -> {

                        Identifier id =
                                BuiltInRegistries.ITEM.getKey(item);

                        String itemId = id.toString();

                        boolean matched = false;

                        for (var entry : overrides.entrySet()) {

                            String selector = entry.getKey();
                            ConfigOverride override = entry.getValue();

                            if (!selectorMatches(selector, item, itemId)) {
                                continue;
                            }

                            if (isExcluded(
                                    override.exclusions,
                                    item,
                                    itemId
                            )) {
                                continue;
                            }

                            matchedSelectors.add(selector);
                            matched = true;
                        }

                        return matched;
                    },

                    (builder, item) -> {

                        Identifier id =
                                BuiltInRegistries.ITEM.getKey(item);

                        String itemId = id.toString();

                        for (var selectorEntry : overrides.entrySet()) {

                            String selector = selectorEntry.getKey();
                            ConfigOverride override = selectorEntry.getValue();

                            if (!selectorMatches(selector, item, itemId)) {
                                continue;
                            }

                            if (isExcluded(
                                    override.exclusions,
                                    item,
                                    itemId
                            )) {
                                continue;
                            }

                            applyComponents(
                                    builder,
                                    item,
                                    itemId,
                                    selector,
                                    override.components,
                                    registryOps,
                                    applyCounts
                            );
                        }
                    }
            );

            logApplySummary(applyCounts);

            for (String configuredSelector : overrides.keySet()) {

                if (!matchedSelectors.contains(configuredSelector)) {

                    LOGGER.warn(
                            "Selector {} did not match any items. "
                                    + "Check whether the mod is installed "
                                    + "and the item/tag ID is correct.",
                            configuredSelector
                    );
                }
            }

            writeComponentReference();
            writeAttributeReference();
            writeTagReference();
            extractBundledDocs();
        });

        LOGGER.info(
                "ItemTweaker loaded {} configured selector(s).",
                overrides.size()
        );
    }

    private static boolean selectorMatches(
            String selector,
            Item item,
            String itemId
    ) {

        if (selector.equals("*")) {
            return true;
        }

        if (selector.startsWith("#")) {

            Identifier tagId =
                    Identifier.tryParse(selector.substring(1));

            if (tagId == null) {
                return false;
            }

            TagKey<Item> tagKey = TagKey.create(
                    BuiltInRegistries.ITEM.key(),
                    tagId
            );

            return BuiltInRegistries.ITEM
                    .wrapAsHolder(item)
                    .is(tagKey);
        }

        return selector.equals(itemId);
    }

    private static boolean isExcluded(
            List<String> exclusions,
            Item item,
            String itemId
    ) {

        for (String exclusion : exclusions) {

            if (selectorMatches(exclusion, item, itemId)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isInvalidSelector(
            String selector,
            boolean allowWildcard
    ) {

        if (selector.equals("*")) {
            return !allowWildcard;
        }

        String idPart = selector.startsWith("#")
                ? selector.substring(1)
                : selector;

        return Identifier.tryParse(idPart) == null;
    }

    private static void applyComponents(
            DataComponentMap.Builder builder,
            Item item,
            String itemId,
            String selector,
            JsonObject components,
            RegistryOps<JsonElement> registryOps,
            Map<String, Integer> applyCounts
    ) {

        Set<String> attributesToRemove = new HashSet<>();

        if (components.has("itemtweaker:remove_attributes")
                && components.get("itemtweaker:remove_attributes")
                .isJsonArray()) {

            JsonArray array = components
                    .getAsJsonArray("itemtweaker:remove_attributes");

            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    attributesToRemove.add(element.getAsString());
                }
            }
        }

        for (var entry : components.entrySet()) {

            String componentId = entry.getKey();

            if (componentId.equals("itemtweaker:remove_attributes")) {

                if (!components.has("minecraft:attribute_modifiers")) {

                    ItemAttributeModifiers stripped =
                            removeAttributeModifiers(
                                    item,
                                    attributesToRemove
                            );

                    if (stripped != null) {

                        setComponent(
                                builder,
                                DataComponents.ATTRIBUTE_MODIFIERS,
                                stripped
                        );

                        countApply(
                                applyCounts,
                                selector,
                                "itemtweaker:remove_attributes"
                        );
                    }
                }

                continue;
            }

            Identifier componentIdentifier =
                    Identifier.tryParse(componentId);

            if (componentIdentifier == null) {
                LOGGER.warn("Invalid component ID: {}", componentId);
                continue;
            }

            DataComponentType<?> componentType =
                    BuiltInRegistries.DATA_COMPONENT_TYPE
                            .getValue(componentIdentifier);

            if (componentType == null) {
                LOGGER.warn(
                        "Unknown component {} for item {}",
                        componentId,
                        itemId
                );
                continue;
            }

            if (componentType.isTransient()) {
                LOGGER.warn(
                        "Cannot configure transient component {}",
                        componentId
                );
                continue;
            }

            try {

                Object value = decodeComponent(
                        componentType,
                        entry.getValue(),
                        registryOps
                );

                if (value == null) {
                    continue;
                }

                if (componentId.equals(
                        "minecraft:attribute_modifiers")) {

                    value = mergeAttributeModifiers(
                            item,
                            (ItemAttributeModifiers) value,
                            attributesToRemove
                    );
                }

                setComponent(
                        builder,
                        componentType,
                        value
                );

                countApply(applyCounts, selector, componentId);

            } catch (RuntimeException exception) {

                LOGGER.error(
                        "Could not apply component {} to {}",
                        componentId,
                        itemId,
                        exception
                );
            }
        }
    }

    private static ItemAttributeModifiers mergeAttributeModifiers(
            Item item,
            ItemAttributeModifiers userModifiers,
            Set<String> toRemove
    ) {

        ItemAttributeModifiers existing =
                item.components()
                        .get(DataComponents.ATTRIBUTE_MODIFIERS);

        List<ItemAttributeModifiers.Entry> merged = new ArrayList<>();

        if (existing != null) {
            merged.addAll(existing.modifiers());
        }

        if (!toRemove.isEmpty()) {
            merged.removeIf(
                    e -> toRemove.contains(e.modifier().id().toString())
            );
        }

        for (ItemAttributeModifiers.Entry userEntry :
                userModifiers.modifiers()) {

            Identifier userId = userEntry.modifier().id();

            merged.removeIf(
                    e -> e.modifier().id().equals(userId)
            );

            merged.add(userEntry);
        }

        return new ItemAttributeModifiers(merged);
    }

    private static ItemAttributeModifiers removeAttributeModifiers(
            Item item,
            Set<String> toRemove
    ) {

        ItemAttributeModifiers existing =
                item.components()
                        .get(DataComponents.ATTRIBUTE_MODIFIERS);

        if (existing == null) {
            return null;
        }

        List<ItemAttributeModifiers.Entry> kept = new ArrayList<>();

        for (ItemAttributeModifiers.Entry entry : existing.modifiers()) {

            if (!toRemove.contains(entry.modifier().id().toString())) {
                kept.add(entry);
            }
        }

        return new ItemAttributeModifiers(kept);
    }

    private static Map<String, ConfigOverride> loadOverrides() {

        Path configFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("itemtweaker.json");

        try {

            Files.createDirectories(configFile.getParent());

            if (Files.notExists(configFile)) {

                String defaultConfig = """
                {
                  "items": {
                  }
                }
                """;

                Files.writeString(
                        configFile,
                        defaultConfig,
                        StandardCharsets.UTF_8
                );

                LOGGER.info("Created default configuration.");
            }

            try (Reader reader = Files.newBufferedReader(
                    configFile,
                    StandardCharsets.UTF_8
            )) {

                JsonObject root =
                        JsonParser.parseReader(reader).getAsJsonObject();

                JsonObject items = root.getAsJsonObject("items");

                if (items == null) {
                    LOGGER.error(
                            "Configuration is missing its 'items' object."
                    );
                    return Map.of();
                }

                Map<String, ConfigOverride> result = new LinkedHashMap<>();

                for (var itemEntry : items.entrySet()) {

                    String selector = itemEntry.getKey();

                    if (isInvalidSelector(selector, true)) {
                        LOGGER.warn("Invalid selector: {}", selector);
                        continue;
                    }

                    if (!itemEntry.getValue().isJsonObject()) {
                        LOGGER.warn(
                                "Configuration for {} must be an object.",
                                selector
                        );
                        continue;
                    }

                    JsonObject properties =
                            itemEntry.getValue().getAsJsonObject();

                    JsonObject components = new JsonObject();

                    if (properties.has("max_stack_size")) {

                        components.add(
                                "minecraft:max_stack_size",
                                properties.get("max_stack_size").deepCopy()
                        );
                    }

                    if (properties.has("components")
                            && properties.get("components").isJsonObject()) {

                        JsonObject configuredComponents =
                                properties.getAsJsonObject("components");

                        for (var component :
                                configuredComponents.entrySet()) {

                            components.add(
                                    component.getKey(),
                                    component.getValue().deepCopy()
                            );
                        }
                    }

                    for (var entry : properties.entrySet()) {

                        String key = entry.getKey();

                        if (key.equals("max_stack_size")
                                || key.equals("components")
                                || key.equals("exclude")) {

                            continue;
                        }

                        String componentId = key.contains(":")
                                ? key
                                : "minecraft:" + key;

                        components.add(
                                componentId,
                                entry.getValue().deepCopy()
                        );
                    }

                    if (components.isEmpty()) {
                        LOGGER.warn(
                                "No components configured for {}",
                                selector
                        );
                        continue;
                    }

                    List<String> exclusions = new ArrayList<>();

                    if (properties.has("exclude")
                            && properties.get("exclude").isJsonArray()) {

                        for (JsonElement element :
                                properties.getAsJsonArray("exclude")) {

                            if (!element.isJsonPrimitive()) {
                                continue;
                            }

                            String raw = element.getAsString();

                            String exclusion = raw.startsWith("!")
                                    ? raw.substring(1)
                                    : raw;

                            if (isInvalidSelector(exclusion, false)) {
                                LOGGER.warn(
                                        "Invalid exclusion {} on selector {}",
                                        raw,
                                        selector
                                );
                                continue;
                            }

                            exclusions.add(exclusion);
                        }
                    }

                    result.put(
                            selector,
                            new ConfigOverride(components, exclusions)
                    );
                }

                return Collections.unmodifiableMap(
                        new LinkedHashMap<>(result)
                );
            }

        } catch (IOException | RuntimeException exception) {

            LOGGER.error(
                    "Unable to load ItemTweaker configuration.",
                    exception
            );

            return Map.of();
        }
    }

    private static void countApply(
            Map<String, Integer> applyCounts,
            String selector,
            String componentId
    ) {

        String key = selector + "\0" + componentId;
        applyCounts.merge(key, 1, Integer::sum);
    }

    private static void logApplySummary(
            Map<String, Integer> applyCounts
    ) {

        if (applyCounts.isEmpty()) {
            return;
        }

        Map<String, Map<String, Integer>> bySelector = new TreeMap<>();

        for (var entry : applyCounts.entrySet()) {

            String[] parts = entry.getKey().split("\0", 2);
            String selector = parts[0];
            String componentId = parts[1];

            bySelector.putIfAbsent(selector, new TreeMap<>());
            bySelector.get(selector).put(componentId, entry.getValue());
        }

        for (var selectorEntry : bySelector.entrySet()) {

            String selector = selectorEntry.getKey();
            Map<String, Integer> components = selectorEntry.getValue();

            for (var componentEntry : components.entrySet()) {

                String componentId = componentEntry.getKey();
                int count = componentEntry.getValue();

                if (count == 1) {
                    LOGGER.info(
                            "Applied {} via {} (1 item)",
                            componentId,
                            selector
                    );
                } else {
                    LOGGER.info(
                            "Applied {} via {} ({} items)",
                            componentId,
                            selector,
                            count
                    );
                }
            }
        }
    }

    private static void writeComponentReference() {

        Path folder = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("itemtweaker");

        Path output = folder.resolve("components.md");

        try {

            Files.createDirectories(folder);

            Map<String, String> entries = new TreeMap<>();

            BuiltInRegistries.DATA_COMPONENT_TYPE.forEach(type -> {

                Identifier id =
                        BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);

                if (id == null) {
                    return;
                }

                String origin = id.getNamespace().equals("minecraft")
                        ? "Vanilla"
                        : "Modded (" + id.getNamespace() + ")";

                String persistent = type.isTransient() ? "No" : "Yes";

                entries.put(
                        id.toString(),
                        "| `" + id + "` | " + origin
                                + " | " + persistent + " |"
                );
            });

            StringBuilder document = new StringBuilder();

            document.append("""
                # ItemTweaker - Component Reference

                Automatically generated from the registered
                Minecraft data-component registry.

                Do not edit this file manually. It is regenerated
                when ItemTweaker starts.

                ## How to read this document

                - Vanilla: Component registered under minecraft.
                - Modded: Component registered by another namespace.
                - Persistent: Has persistent component serialization.
                - Non-persistent (transient) components cannot be
                  configured through JSON.

                All persistent components are decoded with a
                registry-aware codec, so components that reference
                registries (attributes, enchantments, etc.) are
                fully supported.

                ## Registered Components

                | Component ID | Origin | Persistent |
                |---|---|---|
                """);

            for (String entry : entries.values()) {
                document.append(entry).append('\n');
            }

            Files.writeString(
                    output,
                    document.toString(),
                    StandardCharsets.UTF_8
            );

            LOGGER.info(
                    "Generated component reference with {} entries.",
                    entries.size()
            );

        } catch (IOException exception) {

            LOGGER.error(
                    "Failed to generate component reference.",
                    exception
            );
        }
    }

    private static void writeAttributeReference() {

        Path folder = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("itemtweaker");

        Path output = folder.resolve("attributes.md");

        try {

            Files.createDirectories(folder);

            Map<String, String> entries = new TreeMap<>();

            BuiltInRegistries.ATTRIBUTE.forEach(attribute -> {

                Identifier id =
                        BuiltInRegistries.ATTRIBUTE.getKey(attribute);

                if (id == null) {
                    return;
                }

                String origin = id.getNamespace().equals("minecraft")
                        ? "Vanilla"
                        : "Modded (" + id.getNamespace() + ")";

                entries.put(
                        id.toString(),
                        "| `" + id + "` | " + origin + " |"
                );
            });

            StringBuilder document = new StringBuilder();

            document.append("""
                # ItemTweaker - Attribute Reference

                Automatically generated from the registered
                Minecraft attribute registry.

                Do not edit this file manually. It is regenerated
                when ItemTweaker starts.

                Use these IDs as the "type" value inside
                minecraft:attribute_modifiers entries.

                Note: Modern Minecraft attribute IDs do not use
                the generic. prefix. For example, use
                minecraft:attack_damage, not
                minecraft:generic.attack_damage.

                ## Registered Attributes

                | Attribute ID | Origin |
                |---|---|
                """);

            for (String entry : entries.values()) {
                document.append(entry).append('\n');
            }

            Files.writeString(
                    output,
                    document.toString(),
                    StandardCharsets.UTF_8
            );

            LOGGER.info(
                    "Generated attribute reference with {} entries.",
                    entries.size()
            );

        } catch (IOException exception) {

            LOGGER.error(
                    "Failed to generate attribute reference.",
                    exception
            );
        }
    }

    private static void writeTagReference() {

        Path folder = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("itemtweaker");

        Path output = folder.resolve("tags.md");

        try {

            Files.createDirectories(folder);

            Map<String, String> entries = new TreeMap<>();

            BuiltInRegistries.ITEM.getTags().forEach(pair -> {

                TagKey<Item> tagKey = pair.key();
                Identifier id = tagKey.location();

                int count = (int) BuiltInRegistries.ITEM.listElements()
                        .filter(holder -> holder.is(tagKey))
                        .count();

                String origin = id.getNamespace().equals("minecraft")
                        ? "Vanilla"
                        : "Modded (" + id.getNamespace() + ")";

                entries.put(
                        id.toString(),
                        "| `#" + id + "` | " + origin
                                + " | " + count + " |"
                );
            });

            StringBuilder document = new StringBuilder();

            document.append("""
                # ItemTweaker - Item Tag Reference

                Automatically generated from the registered
                Minecraft item tag registry.

                Do not edit this file manually. It is regenerated
                when ItemTweaker starts.

                Use these IDs as selectors in your config,
                prefixed with `#`. For example:

                    "#minecraft:swords": { ... }

                You can also use `"*"` as a special selector
                that matches every registered item.

                ## Registered Item Tags

                | Tag ID | Origin | Items |
                |---|---|---|
                """);

            for (String entry : entries.values()) {
                document.append(entry).append('\n');
            }

            Files.writeString(
                    output,
                    document.toString(),
                    StandardCharsets.UTF_8
            );

            LOGGER.info(
                    "Generated tag reference with {} entries.",
                    entries.size()
            );

        } catch (IOException exception) {

            LOGGER.error(
                    "Failed to generate tag reference.",
                    exception
            );
        }
    }

    private static void extractBundledDocs() {

        Path folder = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("itemtweaker");

        String[] bundledFiles = {
                "README.md"
        };

        try {

            Files.createDirectories(folder);

            for (String name : bundledFiles) {

                Path target = folder.resolve(name);

                if (Files.exists(target)) {
                    continue;
                }

                String resourcePath =
                        "/assets/itemtweaker/docs/" + name;

                try (InputStream in =
                             ItemTweaker.class
                                     .getResourceAsStream(resourcePath)) {

                    if (in == null) {
                        LOGGER.warn(
                                "Bundled doc {} not found in jar.",
                                resourcePath
                        );
                        continue;
                    }

                    Files.copy(in, target);

                    LOGGER.info(
                            "Extracted bundled doc: {}",
                            name
                    );
                }
            }

        } catch (IOException exception) {

            LOGGER.error(
                    "Failed to extract bundled documentation.",
                    exception
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object decodeComponent(
            DataComponentType<?> componentType,
            JsonElement json,
            RegistryOps<JsonElement> ops
    ) {

        Codec codec = componentType.codecOrThrow();

        return codec.parse(ops, json)
                .resultOrPartial(error ->
                        LOGGER.warn(
                                "Invalid component value: {}",
                                error
                        )
                )
                .orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setComponent(
            DataComponentMap.Builder builder,
            DataComponentType<?> componentType,
            Object value
    ) {

        builder.set(
                (DataComponentType) componentType,
                value
        );
    }
}