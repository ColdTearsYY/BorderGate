package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskDifficultyPolicyTest {
    @Test void scalesAutomaticTargetsWithoutChangingSource() {
        var source = Map.of("item:STONE", new BigDecimal("7"), "vault", new BigDecimal("12.5"));
        assertEquals(new BigDecimal("11"), TaskDifficultyPolicy.scaleTargets(source, new BigDecimal("1.5")).get("item:STONE"));
        assertEquals(new BigDecimal("18.75"), TaskDifficultyPolicy.scaleTargets(source, new BigDecimal("1.5")).get("vault"));
        assertEquals(new BigDecimal("7"), source.get("item:STONE"));
    }

    @Test void fractionalItemAndPlayerCurrencyTargetsRoundUpToIntegers() {
        var result = TaskDifficultyPolicy.scaleTargets(Map.of("item:DIAMOND", BigDecimal.ONE,
                "playercurrency:coin", new BigDecimal("2")), new BigDecimal("0.1"));
        assertEquals(BigDecimal.ONE, result.get("item:DIAMOND"));
        assertEquals(BigDecimal.ONE, result.get("playercurrency:coin"));
        assertEquals(0, result.get("item:DIAMOND").stripTrailingZeros().scale());
    }

    @Test void rejectsNonPositiveDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> TaskDifficultyPolicy.scaleTargets(Map.of("vault", BigDecimal.ONE), BigDecimal.ZERO));
    }
}
