package dev.bordergate.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GoalLedger {
    private final Map<String, BigDecimal> targets;
    private final Map<String, BigDecimal> contributed;

    public GoalLedger(Map<String, BigDecimal> targets, Map<String, BigDecimal> contributed) {
        this.targets = clean(targets);
        this.contributed = new LinkedHashMap<>();
        if (contributed != null) {
            contributed.forEach((key, value) -> {
                if (this.targets.containsKey(key) && value != null && value.signum() > 0) {
                    this.contributed.put(key, value.min(this.targets.get(key)));
                }
            });
        }
    }

    public static GoalLedger empty(Map<String, BigDecimal> targets) {
        return new GoalLedger(targets, Map.of());
    }

    public boolean contribute(String resource, BigDecimal amount) {
        if (resource == null || amount == null || amount.signum() <= 0) return false;
        BigDecimal target = targets.get(resource);
        if (target == null) return false;
        BigDecimal current = contributed.getOrDefault(resource, BigDecimal.ZERO);
        if (amount.compareTo(target.subtract(current)) > 0) return false;
        contributed.put(resource, current.add(amount));
        return true;
    }

    public boolean isComplete() {
        return !targets.isEmpty() && targets.entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                        .compareTo(entry.getValue()) >= 0);
    }

    public BigDecimal remaining(String resource) {
        BigDecimal target = targets.get(resource);
        return target == null ? BigDecimal.ZERO : target.subtract(contributed.getOrDefault(resource, BigDecimal.ZERO));
    }

    public Map<String, BigDecimal> targets() {
        return Collections.unmodifiableMap(targets);
    }

    public Map<String, BigDecimal> contributed() {
        return Collections.unmodifiableMap(contributed);
    }

    private static Map<String, BigDecimal> clean(Map<String, BigDecimal> input) {
        Objects.requireNonNull(input, "targets");
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && value.signum() > 0) result.put(key, value);
        });
        return result;
    }
}
