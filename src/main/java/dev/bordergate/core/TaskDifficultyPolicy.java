package dev.bordergate.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies an explicit configuration difficulty factor without changing built-in templates. */
public final class TaskDifficultyPolicy {
    private TaskDifficultyPolicy() { }

    public static Map<String, BigDecimal> scaleTargets(Map<String, BigDecimal> targets, BigDecimal multiplier) {
        return scaleTargets(targets, multiplier, Map.of());
    }

    public static Map<String, BigDecimal> scaleTargets(Map<String, BigDecimal> targets, BigDecimal multiplier,
                                                       Map<String, BigDecimal> resourceMultipliers) {
        if (targets == null || multiplier == null || multiplier.signum() <= 0) throw new IllegalArgumentException("Target multiplier must be positive");
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        targets.forEach((resource, amount) -> {
            BigDecimal factor = multiplier.multiply(resourceMultipliers.getOrDefault(resource, BigDecimal.ONE));
            if (factor.signum() <= 0) throw new IllegalArgumentException("Resource multiplier must be positive: " + resource);
            BigDecimal value = amount.multiply(factor);
            if (resource.startsWith("item:") || resource.startsWith("playercurrency:")) {
                value = value.setScale(0, RoundingMode.CEILING).max(BigDecimal.ONE);
            }
            if (value.signum() <= 0) throw new IllegalArgumentException("Scaled target must be positive: " + resource);
            scaled.put(resource, value.stripTrailingZeros());
        });
        return Map.copyOf(scaled);
    }
}
