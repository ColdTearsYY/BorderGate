package dev.bordergate;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ResourceDisplayTest {
    private static final Map<String, String> KEYS = Map.of("CAULDRON", "block.minecraft.cauldron",
            "IRON_NUGGET", "item.minecraft.iron_nugget", "RAW_IRON", "item.minecraft.raw_iron");
    private Component name(String id) { return ResourceDisplay.name(id, ignored -> null, KEYS::get); }

    @Test void screenshotItemsUseClientTranslationKeysNotInternalIds() {
        for (String material : KEYS.keySet()) {
            var component = assertInstanceOf(TranslatableComponent.class, name("item:" + material));
            assertEquals(KEYS.get(material), component.key());
        }
    }
    @Test void translatedNameSurvivesStyledLoreInterpolation() {
        Component lore = ResourceDisplay.format("&b{resource} &7缺 12，背包可投入 0", name("item:CAULDRON"));
        assertTrue(hasKey(lore, "block.minecraft.cauldron"));
        assertFalse(PlainTextComponentSerializer.plainText().serialize(lore).contains("item:"));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(lore).contains("缺 12"));
        assertEquals(TextDecoration.State.FALSE, lore.decoration(TextDecoration.ITALIC));
    }
    @Test void configuredNamesOverrideTranslationsWithoutChangingResourceId() {
        String id = "item:IRON_NUGGET";
        Component result = ResourceDisplay.name(id, r -> r.equals(id) ? "&6服务器铁粒" : null,
                ignored -> { throw new AssertionError("override should win"); });
        assertEquals("服务器铁粒", PlainTextComponentSerializer.plainText().serialize(result));
        assertEquals(NamedTextColor.GOLD, result.color());
        assertEquals("item:IRON_NUGGET", id);
    }
    @Test void currencyDefaultsAreReadableAndConfigurable() {
        assertEquals("金币", PlainTextComponentSerializer.plainText().serialize(name("vault")));
        assertEquals("点券", PlainTextComponentSerializer.plainText().serialize(name("playerpoints")));
        assertEquals("货币（coin）", PlainTextComponentSerializer.plainText().serialize(name("playercurrency:coin")));
    }
    @Test void invalidItemDoesNotLeakCodeOrPretendToBeAnotherItem() {
        assertEquals("未知物品", PlainTextComponentSerializer.plainText().serialize(name("item:INVALID")));
    }
    @Test void legacySectionColorsAndAllPlaceholdersArePreserved() {
        Component result = ResourceDisplay.format("§e{resource} / {resource}", name("item:RAW_IRON"));
        assertEquals(NamedTextColor.YELLOW, result.color());
        assertEquals(2, keyCount(result, "item.minecraft.raw_iron"));
        assertFalse(PlainTextComponentSerializer.plainText().serialize(result).contains("{resource}"));
    }
    private static boolean hasKey(Component c, String key) { return keyCount(c, key) > 0; }
    private static int keyCount(Component c, String key) {
        return (c instanceof TranslatableComponent t && t.key().equals(key) ? 1 : 0)
                + c.children().stream().mapToInt(child -> keyCount(child, key)).sum();
    }
}
