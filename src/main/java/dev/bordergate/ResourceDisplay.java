package dev.bordergate;

import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Presentation only: never replaces the resource ID used by payments or claims. */
final class ResourceDisplay {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private ResourceDisplay() {}

    static Component name(String resource, org.bukkit.configuration.ConfigurationSection config) {
        return name(resource, id -> config.getString("resources.names." + id), materialName -> {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName);
            return material != null && material.isItem() && !material.isAir() ? material.translationKey() : null;
        });
    }

    static Component name(String resource, Function<String, String> configuredNames,
                          Function<String, String> itemTranslationKeys) {
        String configured = configuredNames.apply(resource);
        if (configured != null && !configured.isBlank()) return text(configured);
        if (resource.startsWith("item:")) {
            String key = itemTranslationKeys.apply(resource.substring(5));
            return key == null || key.isBlank() ? Component.text("未知物品") : Component.translatable(key);
        }
        return Component.text(switch (resource) {
            case "vault" -> "金币";
            case "playerpoints" -> "点券";
            default -> resource.startsWith("playercurrency:") ? "货币（" + resource.substring(15) + "）" : "自定义资源";
        });
    }

    static Component text(String value) {
        return LEGACY.deserialize(value == null ? "" : value.replace('§', '&'))
                .decoration(TextDecoration.ITALIC, false);
    }

    static Component format(String template, Component resourceName) {
        return text(template).replaceText(builder -> builder.matchLiteral("{resource}").replacement(resourceName));
    }
}
