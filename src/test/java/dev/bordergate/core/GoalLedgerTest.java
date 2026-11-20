package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoalLedgerTest {
    @Test
    void tracksOnlyConfiguredResourceWithoutOvershoot() {
        GoalLedger ledger = GoalLedger.empty(Map.of("vault", new BigDecimal("10.00"), "item:DIAMOND", new BigDecimal("2")));
        assertTrue(ledger.contribute("vault", new BigDecimal("3")));
        assertFalse(ledger.contribute("playerpoints", new BigDecimal("1")));
        assertFalse(ledger.contribute("vault", new BigDecimal("8")));
        assertEquals(new BigDecimal("7.00"), ledger.remaining("vault"));
        assertFalse(ledger.isComplete());
        assertTrue(ledger.contribute("vault", new BigDecimal("7")));
        assertTrue(ledger.contribute("item:DIAMOND", new BigDecimal("2")));
        assertTrue(ledger.isComplete());
    }

    @Test
    void rejectsNonPositiveAmountsAndRestoresProgress() {
        GoalLedger ledger = new GoalLedger(Map.of("playercurrency:coin", new BigDecimal("5")),
                Map.of("playercurrency:coin", new BigDecimal("2"), "unconfigured", new BigDecimal("99")));
        assertFalse(ledger.contribute("playercurrency:coin", BigDecimal.ZERO));
        assertFalse(ledger.contribute("playercurrency:coin", new BigDecimal("-1")));
        assertEquals(new BigDecimal("3"), ledger.remaining("playercurrency:coin"));
        assertEquals(Map.of("playercurrency:coin", new BigDecimal("2")), ledger.contributed());
    }

    @Test
    void restoresContributionFromYamlAfterRestart(@TempDir Path directory) throws IOException {
        GoalLedger beforeRestart = GoalLedger.empty(Map.of("item:DIAMOND", new BigDecimal("64")));
        assertTrue(beforeRestart.contribute("item:DIAMOND", new BigDecimal("17")));
        YamlConfiguration saved = new YamlConfiguration();
        beforeRestart.targets().forEach((key, value) -> saved.set("cycle.targets." + key, value.toPlainString()));
        beforeRestart.contributed().forEach((key, value) -> saved.set("cycle.contributed." + key, value.toPlainString()));
        Path file = directory.resolve("data.yml");
        saved.save(file.toFile());

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file.toFile());
        Map<String, BigDecimal> targets = readAmounts(loaded.getConfigurationSection("cycle.targets"));
        Map<String, BigDecimal> contributed = readAmounts(loaded.getConfigurationSection("cycle.contributed"));
        GoalLedger afterRestart = new GoalLedger(targets, contributed);
        assertEquals(new BigDecimal("47"), afterRestart.remaining("item:DIAMOND"));
    }

    private Map<String, BigDecimal> readAmounts(ConfigurationSection section) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) values.put(key, new BigDecimal(section.getString(key)));
        return values;
    }
}
