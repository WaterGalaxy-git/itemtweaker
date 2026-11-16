# ItemTweaker

A lightweight Fabric mod that lets you change item properties
from a single JSON config file. No datapacks, no commands,
no code.

Tweak durability, stack sizes, attack attributes, food values,
rarity, enchantments, and any other data component for both
vanilla and modded items. Supports tag-based targeting,
wildcards, and exclusions so you can change entire categories
of items in a single entry.

All changes are fully reversible. Simply remove the mod to restore every item to its original state.

**Requires:** [Fabric API](https://modrinth.com/mod/fabric-api)

---

## Where to configure items

All item modifications belong in:

`config/itemtweaker.json`

You do not need a separate file for every mod or item.

Reference files are generated in `config/itemtweaker/` on startup:

- `README.md` — this guide
- `components.md` — every data component ItemTweaker knows about
- `attributes.md` — every attribute registered by Minecraft and mods
- `tags.md` — every item tag you can target

## Basic structure

Each entry in the `items` object uses a **selector** as its key.
Three kinds of selector are supported:

| Selector              | Matches                |
|-----------------------|------------------------|
| `"namespace:item_id"` | A single specific item |
| `"#namespace:tag_id"` | Every item in that tag |
| `"*"`                 | Every registered item  |

Examples:

- `minecraft:bread` — just bread
- `#minecraft:swords` — every item tagged as a sword (vanilla + modded)
- `*` — every item in the game

Components are placed inside the selector's `components` object.

## JSON value types

Depending on the component, values can be:

- Integer: `32`
- Decimal: `1.5`
- Boolean: `true`
- String: `"example"`
- Object: `{ ... }`
- Array: `[ ... ]`

The correct value structure depends on the component's codec.

## Full example

```json
{
  "items": {
    "minecraft:bread": {
      "components": {
        "minecraft:max_stack_size": 32,
        "minecraft:food": {
          "nutrition": 8,
          "saturation": 1.5,
          "can_always_eat": true
        }
      }
    },
    "#minecraft:swords": {
      "components": {
        "minecraft:max_damage": 5000
      }
    },
    "*": {
      "components": {
        "minecraft:rarity": "epic"
      }
    }
  }
}
```

## Selector precedence

Multiple selectors can match the same item. They are applied
in the order they appear in your config, so **later entries
override earlier ones**.

```json
{
  "items": {
    "*":                       { "components": { "minecraft:max_stack_size": 99 } },
    "#minecraft:swords":       { "components": { "minecraft:max_stack_size": 1 } },
    "minecraft:diamond_sword": { "components": { "minecraft:max_damage": 2000 } }
  }
}
```

In this example, the diamond sword gets:

- `max_stack_size: 1` — from the swords tag (overrides the `*` value of 99)
- `max_damage: 2000` — from its direct selector
- Any other components inherited from `*` that aren't overridden

Order your config from broadest (`*`) to most specific if you
want direct item selectors to win.

## Excluding items from a selector

Any selector entry can carry an optional `exclude` array that
removes specific items or tags from its match set:

```json
{
  "items": {
    "*": {
      "exclude": ["minecraft:bedrock", "#minecraft:swords"],
      "components": {
        "minecraft:rarity": "uncommon"
      }
    }
  }
}
```

That marks every item in the game as "uncommon" **except** bedrock
and anything tagged as a sword.

Exclusions accept the same formats as the main selector:

- `"minecraft:bedrock"` — a specific item
- `"#minecraft:swords"` — every item in a tag
- The leading `!` is optional; `"!minecraft:bedrock"` and
  `"minecraft:bedrock"` mean the same thing inside an `exclude` list.

Exclusions only block their own entry. If another selector in your
config matches the excluded item, that selector still applies.

## Attribute modifiers

`minecraft:attribute_modifiers` takes a **plain array** of modifier objects
(not an object with a `"modifiers"` key).

```json
{
  "minecraft:attribute_modifiers": [
    {
      "type": "minecraft:attack_damage",
      "id": "minecraft:base_attack_damage",
      "amount": 11,
      "operation": "add_value",
      "slot": "mainhand"
    },
    {
      "type": "minecraft:attack_speed",
      "id": "minecraft:base_attack_speed",
      "amount": -2.0,
      "operation": "add_value",
      "slot": "mainhand"
    }
  ]
}
```

### Modifier merging

ItemTweaker **merges** your modifiers with the item's existing defaults.

- If an existing modifier has the same `id` as one of yours,
  yours replaces it.
- Any existing modifier you don't mention is preserved.

So if you only want to change the attack speed of a diamond sword
and leave its attack damage untouched, you only need to specify
the attack speed entry. The damage stays as the item intended.

### Removing an existing modifier

To strip a default modifier entirely, list its `id` under the
special `itemtweaker:remove_attributes` key:

```json
{
  "items": {
    "minecraft:diamond_sword": {
      "components": {
        "itemtweaker:remove_attributes": [
          "minecraft:base_attack_speed"
        ]
      }
    }
  }
}
```

You can combine removal and replacement in a single config entry.
Removals happen before merging, so you can remove a stock modifier
and add a fresh one with any ID in the same block:

```json
{
  "items": {
    "minecraft:netherite_axe": {
      "components": {
        "itemtweaker:remove_attributes": [
          "minecraft:base_attack_speed"
        ],
        "minecraft:attribute_modifiers": [
          {
            "type": "minecraft:attack_damage",
            "id": "minecraft:base_attack_damage",
            "amount": 15,
            "operation": "add_value",
            "slot": "mainhand"
          }
        ]
      }
    }
  }
}
```

### Player base values

The player's base values are added on top of your amounts:

| Stat          | Player base | `amount` for target of… |
|---------------|-------------|-------------------------|
| Attack damage | 1           | 11 → 12 total           |
| Attack speed  | 4           | -2.0 → 2.0 total        |

See `attributes.md` for the full list of valid attribute IDs.

### Hiding modifiers in tooltips

Use the separate `minecraft:tooltip_display` component:

```json
{
  "minecraft:tooltip_display": {
    "hidden_components": ["minecraft:attribute_modifiers"]
  }
}
```

## Finding components, attributes, and tags

The `config/itemtweaker/` folder contains three generated
references regenerated on every startup:

- `components.md` — data components you can configure
- `attributes.md` — attribute IDs for `attribute_modifiers`
- `tags.md` — item tags you can use as `#tag` selectors

The references contain IDs, not complete usage instructions.
Check the relevant Minecraft wiki page for each component's
JSON structure.

## Important

- Always use valid JSON.
- Item IDs and tag IDs must be registered by a loaded mod.
- Component IDs must be registered.
- Invalid component values are reported in the log.
- Default-component changes require a restart.
- Not every item property is a data component.
- Changes are non-destructive and fully reversible. Removing
  the mod restores all items to their original defaults with
  no leftover data in your world.

## Troubleshooting

| Log message                                  | Cause                                                              |
|----------------------------------------------|--------------------------------------------------------------------|
| `Not a json array`                           | `attribute_modifiers` must be `[...]`, not `{"modifiers": [...]}`. |
| `Selector … did not match any items`         | Typo, missing mod, or an empty tag.                                |
| `Unknown component`                          | Misspelled, or from an unloaded mod.                               |
| `Cannot configure transient component`       | Runtime-only component; cannot be configured from JSON.            |
| `Invalid selector`                           | Selector isn't a valid ID, `#tag`, or `*`.                         |
| `Invalid exclusion`                          | An entry inside `exclude` isn't a valid item ID, `#tag`, or `*`.   |