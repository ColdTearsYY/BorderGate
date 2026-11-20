package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationPlannerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void nullCycleYieldsEmptyPlan() {
        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(null, Map.of(), Map.of(), resource -> 1.0);
        assertTrue(plan.path().isEmpty());
        assertTrue(plan.recommendations().isEmpty());
    }

    @Test
    void completeCycleYieldsEmptyPlan() {
        QuestCycle cycle = cycle(1, Map.of("item:DIAMOND", dec("10")));
        contribute(cycle, "item:DIAMOND", "10");

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), resource -> 1.0);
        assertTrue(plan.path().isEmpty());
        assertTrue(plan.recommendations().isEmpty());
    }

    @Test
    void threeOfFourAsksForExactlyOneMoreResource() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("a", dec("10"));
        targets.put("b", dec("10"));
        targets.put("c", dec("10"));
        targets.put("d", dec("10"));
        QuestCycle cycle = cycle(3, targets);
        contribute(cycle, "a", "10");
        contribute(cycle, "b", "10");

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), resource -> 1.0);
        assertEquals(List.of("c"), plan.path());
        assertEquals(1, plan.recommendations().size());

        contribute(cycle, "c", "10");
        RecommendationPlanner.Plan afterwards = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), resource -> 1.0);
        assertTrue(afterwards.path().isEmpty(), "the optional fourth resource must not be encouraged");
    }

    @Test
    void backpackFinishesResourceFirst() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("partial", dec("100"));
        targets.put("ready", dec("10"));
        QuestCycle cycle = cycle(1, targets);

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle,
                Map.of("partial", dec("1"), "ready", dec("10")), Map.of(), resource -> 1.0);

        assertEquals(List.of("ready"), plan.path());
        RecommendationPlanner.Recommendation first = plan.recommendations().get(0);
        assertEquals("ready", first.resource());
        assertAmount("10", first.remaining());
        assertAmount("10", first.available());
        assertTrue(first.finishesResource());
    }

    @Test
    void availableIsClampedToRemainingGap() {
        QuestCycle cycle = cycle(1, Map.of("a", dec("10")));
        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle,
                Map.of("a", dec("50")), Map.of(), resource -> 1.0);

        RecommendationPlanner.Recommendation recommendation = plan.recommendations().get(0);
        assertAmount("10", recommendation.remaining());
        assertAmount("10", recommendation.available());
        assertTrue(recommendation.finishesResource());
    }

    @Test
    void unclaimedUsesPromisesWithoutBlockingContribution() {
        QuestCycle cycle = cycle(1, Map.of("a", dec("10")));

        RecommendationPlanner.Recommendation partial = RecommendationPlanner.plan(cycle, Map.of(),
                Map.of("a", dec("4")), resource -> 1.0).recommendations().get(0);
        assertAmount("6", partial.unclaimed());

        RecommendationPlanner.Plan fullyClaimed = RecommendationPlanner.plan(cycle, Map.of(),
                Map.of("a", dec("15")), resource -> 1.0);
        assertEquals(List.of("a"), fullyClaimed.path(), "claims are advisory and must not hide the resource");
        assertAmount("0", fullyClaimed.recommendations().get(0).unclaimed());

        RecommendationPlanner.Recommendation negative = RecommendationPlanner.plan(cycle, Map.of(),
                Map.of("a", dec("-3")), resource -> 1.0).recommendations().get(0);
        assertAmount("10", negative.unclaimed());
    }

    @Test
    void costlyResourcesSortLater() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("cheap", dec("10"));
        targets.put("pricey", dec("10"));
        QuestCycle cycle = cycle(2, targets);

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, Map.of(), Map.of(),
                resource -> "cheap".equals(resource) ? 1.0 : 50.0);
        assertEquals(List.of("cheap", "pricey"), plan.path());
    }

    @Test
    void nearerProgressBeatsCostlyDistance() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("far", dec("100"));
        targets.put("near", dec("100"));
        QuestCycle cycle = cycle(2, targets);
        contribute(cycle, "near", "99");

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, Map.of(), Map.of(),
                resource -> "near".equals(resource) ? 10.0 : 1.0);
        assertEquals(List.of("near", "far"), plan.path());
    }

    @Test
    void stockedResourcesSortBeforeEmptyOnes() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("stocked", dec("100"));
        targets.put("empty", dec("100"));
        QuestCycle cycle = cycle(2, targets);

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, Map.of("stocked", dec("1")),
                Map.of(), resource -> "stocked".equals(resource) ? 1000.0 : 1.0);
        assertEquals(List.of("stocked", "empty"), plan.path());
        assertFalse(plan.recommendations().get(0).finishesResource());
        assertAmount("1", plan.recommendations().get(0).available());
    }

    @Test
    void invalidCostFallsBackToOne() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("a", dec("10"));
        targets.put("b", dec("10"));
        QuestCycle cycle = cycle(2, targets);

        RecommendationPlanner.Plan nonFinite = RecommendationPlanner.plan(cycle, Map.of(), Map.of(),
                resource -> "a".equals(resource) ? Double.NaN : Double.POSITIVE_INFINITY);
        assertEquals(List.of("a", "b"), nonFinite.path());

        RecommendationPlanner.Plan nonPositive = RecommendationPlanner.plan(cycle, Map.of(), Map.of(),
                resource -> "a".equals(resource) ? 0.0 : -5.0);
        assertEquals(List.of("a", "b"), nonPositive.path());

        RecommendationPlanner.Plan throwing = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), resource -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(List.of("a", "b"), throwing.path());

        RecommendationPlanner.Plan missing = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), null);
        assertEquals(List.of("a", "b"), missing.path());
    }

    @Test
    void equalScoresTieBreakByResourceName() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("beta", dec("10"));
        targets.put("alpha", dec("10"));
        QuestCycle cycle = cycle(2, targets);

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), resource -> 1.0);
        assertEquals(List.of("alpha", "beta"), plan.path());
    }

    @Test
    void ignoresCompletedAndNonPositiveTargets() {
        Map<String, BigDecimal> withZero = new LinkedHashMap<>();
        withZero.put("done", dec("10"));
        withZero.put("zero", BigDecimal.ZERO);
        withZero.put("todo", dec("10"));
        QuestCycle zeroCycle = cycle(3, withZero);
        contribute(zeroCycle, "done", "10");
        RecommendationPlanner.Plan zeroPlan = RecommendationPlanner.plan(zeroCycle, Map.of(), Map.of(), resource -> 1.0);
        assertEquals(List.of("todo"), zeroPlan.path());

        Map<String, BigDecimal> withNegative = new LinkedHashMap<>();
        withNegative.put("done", dec("10"));
        withNegative.put("negative", dec("-5"));
        withNegative.put("todo", dec("10"));
        QuestCycle negativeCycle = cycle(3, withNegative);
        contribute(negativeCycle, "done", "10");
        RecommendationPlanner.Plan negativePlan = RecommendationPlanner.plan(negativeCycle, Map.of(), Map.of(),
                resource -> 1.0);
        assertEquals(List.of("todo"), negativePlan.path());
    }

    @Test
    void nullAndNegativeInventoryAndPromisesAreSafe() {
        QuestCycle cycle = cycle(1, Map.of("a", dec("10")));

        RecommendationPlanner.Recommendation recommendation = RecommendationPlanner.plan(cycle, null, null,
                resource -> 1.0).recommendations().get(0);
        assertAmount("0", recommendation.available());
        assertAmount("10", recommendation.unclaimed());
        assertFalse(recommendation.finishesResource());

        RecommendationPlanner.Recommendation negatives = RecommendationPlanner.plan(cycle,
                Map.of("a", dec("-4")), Map.of("a", dec("-2")), resource -> 1.0).recommendations().get(0);
        assertAmount("0", negatives.available());
        assertAmount("10", negatives.unclaimed());
    }

    @Test
    void doesNotMutateInputsAndReturnsImmutablePlan() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("a", dec("10"));
        targets.put("b", dec("10"));
        targets.put("c", dec("10"));
        QuestCycle cycle = cycle(2, targets);
        contribute(cycle, "a", "10");

        Map<String, BigDecimal> inventory = new LinkedHashMap<>();
        inventory.put("b", dec("2"));
        Map<String, BigDecimal> promised = new LinkedHashMap<>();
        promised.put("c", dec("3"));
        Map<String, BigDecimal> inventoryBefore = new LinkedHashMap<>(inventory);
        Map<String, BigDecimal> promisedBefore = new LinkedHashMap<>(promised);
        Map<String, BigDecimal> contributedBefore = new LinkedHashMap<>(cycle.contributed());
        Map<String, BigDecimal> targetsBefore = new LinkedHashMap<>(cycle.targets());

        RecommendationPlanner.Plan plan = RecommendationPlanner.plan(cycle, inventory, promised, resource -> 1.0);

        assertEquals(1, plan.path().size());
        assertEquals(inventoryBefore, inventory);
        assertEquals(promisedBefore, promised);
        assertEquals(contributedBefore, cycle.contributed());
        assertEquals(targetsBefore, cycle.targets());
        assertEquals(plan.path().size(), plan.recommendations().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.path().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> plan.recommendations().clear());
    }

    private static QuestCycle cycle(int required, Map<String, BigDecimal> targets) {
        return new QuestCycle("coop-101", "Coop", "daily", targets, required, BigDecimal.ONE);
    }

    private static void contribute(QuestCycle cycle, String resource, String amount) {
        assertTrue(cycle.contribute(PLAYER, resource, dec(amount), BigDecimal.ONE));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, dec(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}