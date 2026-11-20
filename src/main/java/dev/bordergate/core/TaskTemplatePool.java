package dev.bordergate.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Built-in, deterministic catalogue of survival-friendly collection goals. */
public final class TaskTemplatePool {
    public record Template(String id, String title, Map<String, BigDecimal> targets, int requiredCount) {
        public Template {
            targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        }
    }

    private record Theme(String title, String[] items) { }

    private static final List<Theme> THEMES = List.of(
        theme("橡木营造", "OAK_LOG", "OAK_PLANKS", "STICK", "OAK_SAPLING", "BARREL"),
        theme("白桦营造", "BIRCH_LOG", "BIRCH_PLANKS", "STICK", "BIRCH_SAPLING", "CHEST"),
        theme("云杉营造", "SPRUCE_LOG", "SPRUCE_PLANKS", "STICK", "SPRUCE_SAPLING", "CRAFTING_TABLE"),
        theme("石材建设", "COBBLESTONE", "STONE", "STONE_BRICKS", "MOSSY_COBBLESTONE", "STONE_SLAB"),
        theme("砂岩工坊", "SAND", "SANDSTONE", "SANDSTONE_SLAB", "GLASS", "SANDSTONE_STAIRS"),
        theme("陶艺工坊", "CLAY_BALL", "BRICK", "FLOWER_POT", "TERRACOTTA", "BRICKS"),
        theme("燃料储备", "COAL", "COAL_BLOCK", "TORCH", "FURNACE", "CAMPFIRE"),
        theme("铁匠备料", "RAW_IRON", "IRON_INGOT", "IRON_NUGGET", "IRON_BLOCK", "CAULDRON"),
        theme("铜艺建设", "RAW_COPPER", "COPPER_INGOT", "COPPER_BLOCK", "CUT_COPPER", "COPPER_GRATE"),
        theme("黄金工坊", "RAW_GOLD", "GOLD_INGOT", "GOLD_NUGGET", "GOLD_BLOCK", "GOLDEN_CARROT"),
        theme("红石工坊", "REDSTONE", "REDSTONE_TORCH", "REPEATER", "COMPARATOR", "PISTON"),
        theme("青金研习", "LAPIS_LAZULI", "LAPIS_BLOCK", "BLUE_DYE", "BOOK", "ENCHANTING_TABLE"),
        theme("石英建设", "QUARTZ", "QUARTZ_BLOCK", "QUARTZ_SLAB", "QUARTZ_STAIRS", "DAYLIGHT_DETECTOR"),
        theme("麦田丰收", "WHEAT", "WHEAT_SEEDS", "BREAD", "HAY_BLOCK", "CAKE"),
        theme("胡萝卜丰收", "CARROT", "GOLDEN_CARROT", "RABBIT_STEW", "COMPOSTER", "CARROT_ON_A_STICK"),
        theme("土豆丰收", "POTATO", "BAKED_POTATO", "SMOKER", "COMPOSTER", "FURNACE"),
        theme("甜菜丰收", "BEETROOT", "BEETROOT_SEEDS", "BEETROOT_SOUP", "RED_DYE", "COMPOSTER"),
        theme("南瓜丰收", "PUMPKIN", "PUMPKIN_SEEDS", "PUMPKIN_PIE", "JACK_O_LANTERN", "CARVED_PUMPKIN"),
        theme("西瓜丰收", "MELON", "MELON_SLICE", "MELON_SEEDS", "GLISTERING_MELON_SLICE", "COMPOSTER"),
        theme("菌菇料理", "RED_MUSHROOM", "BROWN_MUSHROOM", "MUSHROOM_STEW", "RABBIT_STEW", "SUSPICIOUS_STEW"),
        theme("渔获储备", "COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "COOKED_COD"),
        theme("牧场储备", "BEEF", "LEATHER", "MILK_BUCKET", "COOKED_BEEF", "LEAD"),
        theme("羊毛织造", "WHITE_WOOL", "STRING", "WHITE_CARPET", "WHITE_BED", "SHEARS"),
        theme("家禽储备", "CHICKEN", "EGG", "FEATHER", "COOKED_CHICKEN", "CAKE"),
        theme("蜂蜜工坊", "HONEYCOMB", "HONEY_BOTTLE", "BEEHIVE", "HONEYCOMB_BLOCK", "CANDLE"),
        theme("海洋采集", "KELP", "DRIED_KELP", "SEAGRASS", "NAUTILUS_SHELL", "PRISMARINE_SHARD"),
        theme("花园建设", "POPPY", "DANDELION", "AZURE_BLUET", "OXEYE_DAISY", "FLOWER_POT"),
        theme("纸书工坊", "SUGAR_CANE", "PAPER", "BOOK", "BOOKSHELF", "MAP"),
        theme("皮革工坊", "LEATHER", "RABBIT_HIDE", "LEATHER_CHESTPLATE", "ITEM_FRAME", "SADDLE"),
        theme("旅途补给", "TORCH", "BREAD", "LADDER", "CAMPFIRE", "CHEST")
    );

    private static final List<Template> CATALOGUE = buildCatalogue();

    private TaskTemplatePool() { }

    public static List<Template> catalogue() {
        return CATALOGUE;
    }

    public static Template select(String seed, String cadence, Set<String> excludedIds, Predicate<String> available) {
        int multiplier = switch (cadence) {
            case "daily" -> 5;
            case "weekly" -> 7;
            case "monthly" -> 14;
            default -> throw new IllegalArgumentException("Unknown task cadence: " + cadence);
        };
        List<Template> compatible = CATALOGUE.stream()
                .filter(t -> t.targets().keySet().stream().allMatch(available))
                .toList();
        if (compatible.isEmpty()) return null;
        List<Template> candidates = compatible.stream().filter(t -> !excludedIds.contains(t.id())).toList();
        if (candidates.isEmpty()) candidates = compatible;
        Template selected = candidates.get(index(seed, cadence, excludedIds, candidates.size()));
        if (multiplier == 1) return selected;
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        selected.targets().forEach((key, amount) -> scaled.put(key, amount.multiply(BigDecimal.valueOf(multiplier))));
        return new Template(selected.id(), selected.title(), scaled, selected.requiredCount());
    }

    private static List<Template> buildCatalogue() {
        List<Template> result = new ArrayList<>(150);
        for (int themeIndex = 0; themeIndex < THEMES.size(); themeIndex++) {
            Theme theme = THEMES.get(themeIndex);
            for (int variant = 0; variant < 5; variant++) {
                Map<String, BigDecimal> targets = new LinkedHashMap<>();
                for (int slot = 0; slot < 4; slot++) {
                    String material = theme.items()[(slot + variant) % theme.items().length];
                    int base = baseQuantity(material);
                    int amount = base + base * ((themeIndex + variant + slot) % 3) / 4;
                    targets.put("item:" + material, BigDecimal.valueOf(amount));
                }
                String id = String.format("collection-%02d-%d", themeIndex + 1, variant + 1);
                result.add(new Template(id, theme.title() + " " + (variant + 1), targets, variant % 2 == 0 ? 3 : 4));
            }
        }
        return List.copyOf(result);
    }

    private static int baseQuantity(String material) {
        return switch (material) {
            case "ENCHANTING_TABLE", "CAKE", "WHITE_BED", "SHEARS", "CAULDRON", "LEAD", "SADDLE", "BEEHIVE",
                    "MILK_BUCKET", "CARROT_ON_A_STICK", "DAYLIGHT_DETECTOR", "COMPARATOR" -> 1;
            case "IRON_BLOCK", "GOLD_BLOCK", "LAPIS_BLOCK", "HONEYCOMB_BLOCK", "NAUTILUS_SHELL", "PRISMARINE_SHARD",
                    "RABBIT_HIDE", "RABBIT_STEW", "SUSPICIOUS_STEW", "BEETROOT_SOUP", "MUSHROOM_STEW",
                    "LEATHER_CHESTPLATE", "BOOKSHELF", "MAP", "PISTON", "REPEATER" -> 4;
            case "FURNACE", "SMOKER", "CAMPFIRE", "CHEST", "BARREL", "COMPOSTER", "CRAFTING_TABLE", "FLOWER_POT", "ITEM_FRAME" -> 4;
            case "RAW_GOLD", "GOLD_INGOT", "HONEY_BOTTLE", "HONEYCOMB", "PUFFERFISH", "TROPICAL_FISH" -> 8;
            case "RAW_IRON", "IRON_INGOT", "RAW_COPPER", "COPPER_INGOT", "LEATHER", "BOOK", "CANDLE" -> 16;
            default -> 32;
        };
    }

    private static Theme theme(String title, String... items) {
        return new Theme(title, items);
    }

    private static int index(String seed, String cadence, Set<String> excludedIds, int bound) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
            excludedIds.stream().sorted().forEach(id -> {
                digest.update((byte) 0);
                digest.update(id.getBytes(StandardCharsets.UTF_8));
            });
            byte[] hash = digest.digest();
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | (hash[i] & 0xffL);
            return (int) Long.remainderUnsigned(value, bound);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
