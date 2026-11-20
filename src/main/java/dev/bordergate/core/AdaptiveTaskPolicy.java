package dev.bordergate.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, stateless difficulty adaptation for newly generated quest tasks.
 *
 * <p>Callers settle every task once after it finishes and pass the resulting
 * observations in completion order (oldest first, newest last). The policy
 * de-duplicates by task id inside itself, so a caller that keeps the whole
 * history never double-counts a replayed settlement.</p>
 */
public final class AdaptiveTaskPolicy {
    private static final double MIN_SCALE = 0.5d;
    private static final double MAX_SCALE = 2.0d;
    private static final double REDUCE_FACTOR = 0.9d;
    private static final double INCREASE_FACTOR = 1.1d;
    private static final double FAST_ELAPSED = 0.5d;
    private static final int WINDOW = 6;
    private static final int MIN_SAMPLES = 3;

    /** A single settled task observation. Fractions are ratios, normally 0..1. */
    public record Observation(String taskId, String cadence, boolean success, double completionFraction,
                              double elapsedFraction, int participants, int activePlayers,
                              Map<String, Double> resourceFractions) {
        public Observation {
            resourceFractions = immutableFractions(resourceFractions);
        }
    }

    private AdaptiveTaskPolicy() {
    }

    /**
     * Next global difficulty scale for the given cadence, clamped to 0.5..2.
     * Returns the sanitized current scale during cold start (fewer than three
     * distinct task observations for that cadence).
     */
    public static double nextScale(double current, List<Observation> history, String cadence, int activePlayers) {
        double base = sanitizeScale(current);
        List<Observation> window = window(history, cadence);
        if (window.size() < MIN_SAMPLES) return base;

        int size = window.size();
        int failures = 0;
        int fastSuccesses = 0;
        for (Observation observation : window) {
            if (observation.success()) {
                if (clampFraction(observation.elapsedFraction()) <= FAST_ELAPSED) fastSuccesses++;
            } else {
                failures++;
            }
        }

        boolean failurePressure = failures * 3 >= size * 2;
        boolean populationDrop = populationDrop(window, activePlayers);
        if (failurePressure || populationDrop) return clampScale(base * REDUCE_FACTOR);

        boolean fastMajority = (size - failures) * 3 >= size * 2 && fastSuccesses * 3 >= size * 2;
        boolean populationSurge = populationSurge(window, activePlayers);
        if (fastMajority || populationSurge) return clampScale(base * INCREASE_FACTOR);
        return base;
    }

    /**
     * Scales positive target amounts by the given scale, rounding each result up
     * to an integer of at least one. Invalid scale falls back to 1.0 and is
     * clamped to 0.5..2; invalid targets are skipped. Inputs are not modified.
     */
    public static Map<String, BigDecimal> scaleTargets(Map<String, BigDecimal> targets, double scale) {
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) return Collections.unmodifiableMap(scaled);
        BigDecimal factor = BigDecimal.valueOf(sanitizeScale(scale));
        for (Map.Entry<String, BigDecimal> entry : targets.entrySet()) {
            String resource = entry.getKey();
            BigDecimal amount = entry.getValue();
            if (resource == null || resource.isBlank() || amount == null || amount.signum() <= 0) continue;
            BigDecimal value = amount.multiply(factor).setScale(0, RoundingMode.CEILING);
            if (value.compareTo(BigDecimal.ONE) < 0) value = BigDecimal.ONE;
            scaled.put(resource, value);
        }
        return Collections.unmodifiableMap(scaled);
    }

    /**
     * Resources that failed to make progress in at least three distinct task
     * settlements of the newest window, without a later full success since.
     * The oldest failures age out of the rolling window naturally.
     */
    public static Set<String> stalledResources(List<Observation> history, String cadence) {
        Set<String> stalled = new LinkedHashSet<>();
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (Observation observation : window(history, cadence)) {
            for (Map.Entry<String, Double> entry : observation.resourceFractions().entrySet()) {
                String resource = entry.getKey();
                double fraction = entry.getValue();
                if (!Double.isFinite(fraction)) continue;
                if (observation.success()) {
                    if (fraction >= 1.0d) {
                        failures.remove(resource);
                        stalled.remove(resource);
                    }
                } else if (fraction < 0.25d) {
                    int count = failures.merge(resource, 1, Integer::sum);
                    if (count >= 3) stalled.add(resource);
                }
            }
        }
        return Collections.unmodifiableSet(stalled);
    }

    /** Newest observation per task id for one cadence, limited to the newest six tasks, oldest first. */
    private static List<Observation> window(List<Observation> history, String cadence) {
        if (history == null || history.isEmpty() || cadence == null || cadence.isBlank()) return List.of();
        Map<String, Observation> newestByTask = new LinkedHashMap<>();
        for (Observation observation : history) {
            if (observation == null || observation.taskId() == null || observation.taskId().isBlank()) continue;
            if (!cadence.equals(observation.cadence())) continue;
            newestByTask.remove(observation.taskId());
            newestByTask.put(observation.taskId(), observation);
        }
        List<Observation> ordered = new ArrayList<>(newestByTask.values());
        int from = Math.max(0, ordered.size() - WINDOW);
        return List.copyOf(ordered.subList(from, ordered.size()));
    }

    private static boolean populationDrop(List<Observation> window, int activePlayers) {
        if (activePlayers <= 0) return false;
        double average = historicalActiveAverage(window);
        return average > 0 && activePlayers <= 0.6d * average;
    }

    private static boolean populationSurge(List<Observation> window, int activePlayers) {
        if (activePlayers <= 0) return false;
        double average = historicalActiveAverage(window);
        return average > 0 && activePlayers > 1.5d * average && recentSuccessMajority(window);
    }

    private static double historicalActiveAverage(List<Observation> window) {
        double sum = 0;
        int count = 0;
        for (Observation observation : window) {
            if (observation.activePlayers() > 0) {
                sum += observation.activePlayers();
                count++;
            }
        }
        return count >= MIN_SAMPLES ? sum / count : 0d;
    }

    /** Majority of the newest few observations (latest half, at least one) succeeded. */
    private static boolean recentSuccessMajority(List<Observation> window) {
        int recent = Math.max(1, (window.size() + 1) / 2);
        int successes = 0;
        for (int i = window.size() - recent; i < window.size(); i++) {
            if (window.get(i).success()) successes++;
        }
        return successes * 2 > recent;
    }

    private static double sanitizeScale(double value) {
        if (!Double.isFinite(value)) return 1.0d;
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, value));
    }

    private static double clampScale(double value) {
        if (!Double.isFinite(value)) return 1.0d;
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, value));
    }

    private static double clampFraction(double value) {
        if (Double.isNaN(value)) return 1.0d;
        if (value == Double.POSITIVE_INFINITY) return 1.0d;
        if (value == Double.NEGATIVE_INFINITY) return 0d;
        return Math.min(1.0d, Math.max(0d, value));
    }

    private static Map<String, Double> immutableFractions(Map<String, Double> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, Double> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : input.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            if (key == null || key.isBlank() || value == null || !Double.isFinite(value)) continue;
            copy.put(key, value);
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }
}