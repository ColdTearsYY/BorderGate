package dev.bordergate.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Pure cooperation-gap planner. Given the current {@link QuestCycle} state it
 * recommends exactly the resources that are still needed to reach the required
 * completion count, ordered so that resources the player's backpack can finish
 * come first, then resources the player already holds, then cheap and nearly
 * finished resources.
 *
 * <p>No input is mutated and every returned collection is an immutable
 * defensive copy. The planner never blocks a contribution: claims
 * ({@code promised}) only reduce the reported {@code unclaimed} amount.</p>
 */
public final class RecommendationPlanner {

    private static final MathContext RATIO_CONTEXT = MathContext.DECIMAL128;
    private static final double DEFAULT_COST = 1.0d;

    /**
     * One recommended resource. All amounts are non-negative; {@code available}
     * is clamped to {@code remaining}; {@code finishesResource} is true when the
     * inventory alone covers the remaining gap.
     */
    public record Recommendation(String resource, BigDecimal remaining, BigDecimal available,
                                 BigDecimal unclaimed, boolean finishesResource) {
        public Recommendation {
            if (resource == null || remaining == null || available == null || unclaimed == null) {
                throw new IllegalArgumentException("Recommendation fields must be non-null");
            }
        }
    }

    /**
     * Immutable ordered plan. {@code path} holds the recommended resource names
     * and {@code recommendations} holds the matching details in the same order.
     */
    public record Plan(List<String> path, List<Recommendation> recommendations) {
        public Plan {
            path = path == null ? List.of() : List.copyOf(path);
            recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        }

        public static Plan empty() {
            return new Plan(List.of(), List.of());
        }
    }

    private RecommendationPlanner() {
    }

    /**
     * Builds a recommendation plan. A null or already complete cycle yields an
     * empty plan, as do cycles that need no further resource. Completed targets
     * and non-positive targets are never recommended, and invalid inventory,
     * promise or cost values fall back to safe defaults instead of failing.
     *
     * @param cycle    current quest state, may be {@code null}
     * @param inventory player backpack amounts, may be {@code null} or partial
     * @param promised  advisory claim amounts, may be {@code null} or partial
     * @param cost      positive per-unit cost lookup; {@code null}, non-finite,
     *                  zero or negative results default to {@code 1}
     */
    public static Plan plan(QuestCycle cycle, Map<String, BigDecimal> inventory,
                            Map<String, BigDecimal> promised, ToDoubleFunction<String> cost) {
        if (cycle == null || cycle.isComplete()) {
            return Plan.empty();
        }
        int required = cycle.requiredCount();
        if (required <= 0) {
            return Plan.empty();
        }
        int completed = cycle.completedResourceCount();
        int need = Math.max(0, required - completed);
        if (need == 0) {
            return Plan.empty();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : cycle.targets().entrySet()) {
            String resource = entry.getKey();
            BigDecimal target = entry.getValue();
            if (resource == null || target == null || target.signum() <= 0) {
                continue;
            }
            BigDecimal remaining = cycle.remaining(resource);
            if (remaining == null || remaining.signum() <= 0) {
                continue;
            }
            BigDecimal available = nonNegative(inventory, resource).min(remaining);
            BigDecimal unclaimed = remaining.subtract(nonNegative(promised, resource)).max(BigDecimal.ZERO);
            boolean finishes = available.compareTo(remaining) >= 0;
            candidates.add(new Candidate(resource, remaining, available, unclaimed, finishes,
                    score(remaining, target, cost, resource)));
        }

        candidates.sort(ORDER);
        int selected = Math.min(need, candidates.size());
        List<String> path = new ArrayList<>(selected);
        List<Recommendation> recommendations = new ArrayList<>(selected);
        for (int index = 0; index < selected; index++) {
            Candidate candidate = candidates.get(index);
            path.add(candidate.resource);
            recommendations.add(new Recommendation(candidate.resource, candidate.remaining,
                    candidate.available, candidate.unclaimed, candidate.finishes));
        }
        return new Plan(path, recommendations);
    }

    private static BigDecimal nonNegative(Map<String, BigDecimal> values, String resource) {
        if (values == null || resource == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = values.get(resource);
        return value == null || value.signum() <= 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal score(BigDecimal remaining, BigDecimal target, ToDoubleFunction<String> cost,
                                    String resource) {
        BigDecimal ratio = remaining.divide(target, RATIO_CONTEXT);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }
        return ratio.multiply(BigDecimal.valueOf(sanitizedCost(cost, resource)));
    }

    private static double sanitizedCost(ToDoubleFunction<String> cost, String resource) {
        if (cost == null) {
            return DEFAULT_COST;
        }
        double value;
        try {
            value = cost.applyAsDouble(resource);
        } catch (RuntimeException failure) {
            return DEFAULT_COST;
        }
        return Double.isFinite(value) && value > 0 ? value : DEFAULT_COST;
    }

    private static final Comparator<Candidate> ORDER = Comparator
            .comparing((Candidate candidate) -> !candidate.finishes)
            .thenComparing(candidate -> !(candidate.available.signum() > 0))
            .thenComparing(candidate -> candidate.score)
            .thenComparing(candidate -> candidate.resource);

    private record Candidate(String resource, BigDecimal remaining, BigDecimal available,
                             BigDecimal unclaimed, boolean finishes, BigDecimal score) {
    }
}