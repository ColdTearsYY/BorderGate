package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AdaptiveTaskPolicyTest {
    private static final String DAILY = "daily";
    private static final String WEEKLY = "weekly";
    private static final double EPS = 1e-9;

    private static AdaptiveTaskPolicy.Observation observe(String taskId, String cadence, boolean success,
                                                          double completion, double elapsed, int participants,
                                                          int activePlayers, Map<String, Double> fractions) {
        return new AdaptiveTaskPolicy.Observation(taskId, cadence, success, completion, elapsed, participants,
                activePlayers, fractions);
    }

    private static AdaptiveTaskPolicy.Observation success(String taskId, double elapsed) {
        return observe(taskId, DAILY, true, 1.0, elapsed, 3, 5, Map.of());
    }

    private static AdaptiveTaskPolicy.Observation success(String taskId, double elapsed, int activePlayers) {
        return observe(taskId, DAILY, true, 1.0, elapsed, 3, activePlayers, Map.of());
    }

    private static AdaptiveTaskPolicy.Observation failure(String taskId) {
        return observe(taskId, DAILY, false, 0.2, 1.0, 3, 5, Map.of());
    }

    private static AdaptiveTaskPolicy.Observation failure(String taskId, Map<String, Double> fractions) {
        return observe(taskId, DAILY, false, 0.2, 1.0, 3, 5, fractions);
    }

    private static double nextScale(double current, List<AdaptiveTaskPolicy.Observation> history, int activePlayers) {
        return AdaptiveTaskPolicy.nextScale(current, history, DAILY, activePlayers);
    }

    @Test
    void coldStartKeepsSanitizedCurrentUntilThreeDistinctTasks() {
        List<AdaptiveTaskPolicy.Observation> two = List.of(failure("a"), failure("b"));
        assertEquals(1.0, nextScale(1.0, two, 5), EPS);
        assertEquals(0.6, nextScale(0.6, two, 5), EPS);
        assertEquals(1.0, nextScale(Double.NaN, List.of(), 0), EPS);
        assertEquals(2.0, nextScale(9.0, List.of(), 5), EPS);
        assertEquals(0.5, nextScale(0.1, List.of(), 5), EPS);
        assertEquals(1.0, nextScale(Double.POSITIVE_INFINITY, two, 5), EPS);
    }

    @Test
    void cadenceIsolationIgnoresOtherCadences() {
        List<AdaptiveTaskPolicy.Observation> weekly = List.of(
                observe("a", WEEKLY, false, 0.2, 1.0, 3, 5, Map.of()),
                observe("b", WEEKLY, false, 0.2, 1.0, 3, 5, Map.of()),
                observe("c", WEEKLY, false, 0.2, 1.0, 3, 5, Map.of()));
        assertEquals(1.0, nextScale(1.0, weekly, 5), EPS);
        assertEquals(0.9, AdaptiveTaskPolicy.nextScale(1.0, weekly, WEEKLY, 5), EPS);
        assertEquals(1.0, AdaptiveTaskPolicy.nextScale(1.0, weekly, null, 5), EPS);
        assertEquals(1.0, AdaptiveTaskPolicy.nextScale(1.0, weekly, " ", 5), EPS);
    }

    @Test
    void failureMajorityReducesByTenPercent() {
        assertEquals(0.9, nextScale(1.0, List.of(failure("a"), failure("b"), success("c", 0.2)), 5), EPS);
        assertEquals(0.9, nextScale(1.0, List.of(failure("a"), failure("b"), failure("c"), success("d", 0.2)), 5), EPS);
        // 3 failures out of 5 is 60%, below the 2/3 reduction threshold, so the scale stays put.
        assertEquals(1.0, nextScale(1.0, List.of(failure("a"), failure("b"), failure("c"),
                success("d", 0.2), success("e", 0.2)), 5), EPS);
    }

    @Test
    void belowTwoThirdsFailureStaysInDeadband() {
        // 3 of 5 failures is 60%, below the 2/3 reduction threshold; 2 of 5 successes cannot raise either.
        List<AdaptiveTaskPolicy.Observation> history = List.of(
                failure("a"), failure("b"), failure("c"),
                observe("d", DAILY, true, 1.0, 0.9, 3, 5, Map.of()),
                observe("e", DAILY, true, 1.0, 0.9, 3, 5, Map.of()));
        assertEquals(1.0, nextScale(1.0, history, 5), EPS);
    }

    @Test
    void fastSuccessfulMajorityIncreasesByTenPercent() {
        assertEquals(1.1, nextScale(1.0, List.of(success("a", 0.4), success("b", 0.2), success("c", 0.5)), 5), EPS);
        assertEquals(1.1, nextScale(1.0, List.of(success("a", 0.4), success("b", 0.2),
                observe("c", DAILY, true, 1.0, 0.9, 3, 5, Map.of()), success("d", 0.1)), 5), EPS);
    }

    @Test
    void slowSuccessesKeepCurrentInDeadband() {
        assertEquals(1.0, nextScale(1.0, List.of(success("a", 0.9), success("b", 0.8), success("c", 0.7)), 5), EPS);
        assertEquals(1.0, nextScale(1.0, List.of(success("a", 0.9), success("b", 0.9), failure("c")), 5), EPS);
    }

    @Test
    void populationDropReducesEvenWhenSuccessesWereFast() {
        List<AdaptiveTaskPolicy.Observation> history = List.of(
                success("a", 0.2, 10), success("b", 0.2, 10), success("c", 0.1, 10));
        assertEquals(0.9, nextScale(1.0, history, 3), EPS);
        assertEquals(1.1, populationReference(history), EPS);
    }

    private static double populationReference(List<AdaptiveTaskPolicy.Observation> history) {
        // Same window with the historical population still present must raise, proving the drop is the cause.
        return nextScale(1.0, history, 10);
    }

    @Test
    void populationDropNeedsThreeHistoricalSamplesAndPositiveCaller() {
        List<AdaptiveTaskPolicy.Observation> mostlyUnknown = List.of(
                success("a", 0.9, 10), success("b", 0.9, 10),
                observe("c", DAILY, true, 1.0, 0.9, 3, 0, Map.of()));
        assertEquals(1.0, nextScale(1.0, mostlyUnknown, 1), EPS);
        List<AdaptiveTaskPolicy.Observation> known = List.of(
                success("a", 0.9, 10), success("b", 0.9, 10), success("c", 0.9, 10));
        assertEquals(1.0, nextScale(1.0, known, 0), EPS);
        assertEquals(0.9, nextScale(1.0, known, 1), EPS);
    }

    @Test
    void reductionHasPriorityOverPopulationSurge() {
        // Four failures out of six plus a population surge: failure pressure must win.
        List<AdaptiveTaskPolicy.Observation> history = List.of(
                observe("a", DAILY, false, 0.1, 1.0, 3, 2, Map.of()),
                observe("b", DAILY, false, 0.1, 1.0, 3, 2, Map.of()),
                observe("c", DAILY, false, 0.1, 1.0, 3, 2, Map.of()),
                observe("d", DAILY, false, 0.1, 1.0, 3, 2, Map.of()),
                success("e", 0.1, 2), success("f", 0.1, 2));
        assertEquals(0.9, nextScale(1.0, history, 9), EPS);
    }

    @Test
    void populationSurgeRaisesOnlyWithRecentSuccessMajority() {
        List<AdaptiveTaskPolicy.Observation> history = List.of(
                success("a", 0.9, 2), success("b", 0.9, 2), success("c", 0.9, 2));
        assertEquals(1.1, nextScale(1.0, history, 4), EPS);
        assertEquals(1.0, nextScale(1.0, history, 3), EPS);
        List<AdaptiveTaskPolicy.Observation> recentFailures = List.of(
                success("a", 0.9, 2), success("b", 0.9, 2), success("c", 0.9, 2),
                observe("d", DAILY, false, 0.1, 1.0, 3, 2, Map.of()),
                observe("e", DAILY, false, 0.1, 1.0, 3, 2, Map.of()),
                observe("f", DAILY, false, 0.1, 1.0, 3, 2, Map.of()));
        // Half failures stay under the reduction threshold, and the newest half all failed,
        // so a population surge alone must not raise the scale.
        assertEquals(1.0, nextScale(1.0, recentFailures, 9), EPS);
    }

    @Test
    void scaleIsClampedBetweenHalfAndTwo() {
        assertEquals(2.0, nextScale(2.0, List.of(success("a", 0.1), success("b", 0.1), success("c", 0.1)), 5), EPS);
        assertEquals(2.0, nextScale(1.95, List.of(success("a", 0.1), success("b", 0.1), success("c", 0.1)), 5), EPS);
        assertEquals(0.5, nextScale(0.5, List.of(failure("a"), failure("b"), failure("c")), 5), EPS);
        assertEquals(0.5, nextScale(0.55, List.of(failure("a"), failure("b"), failure("c")), 5), EPS);
        assertEquals(0.54, nextScale(0.6, List.of(failure("a"), failure("b"), failure("c")), 5), EPS);
    }

    @Test
    void negativeFractionsClampAndNaNIsNotFast() {
        assertEquals(1.1, nextScale(1.0, List.of(
                observe("a", DAILY, true, 1.0, -2.0, 3, 5, Map.of()),
                observe("b", DAILY, true, 1.0, -0.1, 3, 5, Map.of()),
                observe("c", DAILY, true, 1.0, 0.2, 3, 5, Map.of())), 5), EPS);
        assertEquals(1.0, nextScale(1.0, List.of(
                observe("a", DAILY, true, 1.0, Double.NaN, 3, 5, Map.of()),
                observe("b", DAILY, true, 1.0, Double.NaN, 3, 5, Map.of()),
                observe("c", DAILY, true, 1.0, Double.NaN, 3, 5, Map.of())), 5), EPS);
        assertEquals(1.1, nextScale(1.0, List.of(
                observe("a", DAILY, true, 1.0, Double.NaN, 3, 5, Map.of()),
                observe("b", DAILY, true, 1.0, 0.2, 3, 5, Map.of()),
                observe("c", DAILY, true, 1.0, 0.3, 3, 5, Map.of())), 5), EPS);
    }

    @Test
    void windowKeepsNewestSixDistinctTaskIds() {
        List<AdaptiveTaskPolicy.Observation> seven = new ArrayList<>();
        seven.add(failure("oldest"));
        for (String id : List.of("b", "c", "d", "e", "f", "g")) seven.add(success(id, 0.2));
        assertEquals(1.1, nextScale(1.0, seven, 5), EPS);

        List<AdaptiveTaskPolicy.Observation> repeatedFailure = List.of(failure("a"), failure("a"), success("b", 0.2));
        assertEquals(1.0, nextScale(1.0, repeatedFailure, 5), EPS);

        List<AdaptiveTaskPolicy.Observation> replay = List.of(
                failure("a"), success("a", 0.2), success("b", 0.2), success("c", 0.2));
        assertEquals(1.1, nextScale(1.0, replay, 5), EPS);
    }

    @Test
    void invalidObservationsAreIgnored() {
        List<AdaptiveTaskPolicy.Observation> history = Arrays.asList(
                null,
                observe(" ", DAILY, false, 0.1, 1.0, 3, 5, Map.of()),
                observe("x", null, false, 0.1, 1.0, 3, 5, Map.of()),
                failure("a"), failure("b"), failure("c"));
        assertEquals(0.9, nextScale(1.0, history, 5), EPS);
        assertTrue(AdaptiveTaskPolicy.stalledResources(history, DAILY).isEmpty());
    }

    @Test
    void observationFractionsAreDefensivelyCopiedAndImmutable() {
        Map<String, Double> mutable = new HashMap<>();
        mutable.put("iron", 0.1);
        mutable.put("gold", Double.NaN);
        AdaptiveTaskPolicy.Observation observation = failure("a", mutable);
        mutable.put("iron", 0.9);
        mutable.put("diamond", 0.0);
        assertEquals(0.1, observation.resourceFractions().get("iron"), EPS);
        assertFalse(observation.resourceFractions().containsKey("diamond"));
        assertThrows(UnsupportedOperationException.class, () -> observation.resourceFractions().put("x", 1.0));

        AdaptiveTaskPolicy.Observation nullMap = observe("b", DAILY, false, 0.0, 1.0, 0, 0, null);
        assertTrue(nullMap.resourceFractions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> nullMap.resourceFractions().put("x", 1.0));
    }

    @Test
    void historyListIsNotMutated() {
        List<AdaptiveTaskPolicy.Observation> history = new ArrayList<>(List.of(failure("a"), failure("b"), failure("c")));
        int size = history.size();
        nextScale(1.0, history, 5);
        AdaptiveTaskPolicy.stalledResources(history, DAILY);
        assertEquals(size, history.size());
        assertEquals("a", history.get(0).taskId());
        assertEquals("c", history.get(2).taskId());
    }

    @Test
    void scaleTargetsRoundsUpToIntegerAtLeastOne() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("a", new BigDecimal("1"));
        targets.put("b", new BigDecimal("3"));
        targets.put("c", new BigDecimal("0.4"));
        targets.put("d", new BigDecimal("1.0"));
        Map<String, BigDecimal> scaled = AdaptiveTaskPolicy.scaleTargets(targets, 1.1);
        assertEquals(new BigDecimal("2"), scaled.get("a"));
        assertEquals(new BigDecimal("4"), scaled.get("b"));
        assertEquals(BigDecimal.ONE, scaled.get("c"));
        assertEquals(new BigDecimal("2"), scaled.get("d"));

        Map<String, BigDecimal> tiny = AdaptiveTaskPolicy.scaleTargets(Map.of("a", new BigDecimal("0.2")), 0.5);
        assertEquals(BigDecimal.ONE, tiny.get("a"));
    }

    @Test
    void scaleTargetsSanitizesScaleAndSkipsInvalidTargets() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("a", new BigDecimal("3"));
        targets.put("zero", BigDecimal.ZERO);
        targets.put("negative", new BigDecimal("-2"));
        targets.put("null", null);
        targets.put(" ", new BigDecimal("4"));

        assertEquals(new BigDecimal("3"), AdaptiveTaskPolicy.scaleTargets(targets, Double.NaN).get("a"));
        assertEquals(new BigDecimal("6"), AdaptiveTaskPolicy.scaleTargets(targets, 10.0).get("a"));
        assertEquals(new BigDecimal("2"), AdaptiveTaskPolicy.scaleTargets(targets, 0.01).get("a"));

        Map<String, BigDecimal> safe = AdaptiveTaskPolicy.scaleTargets(targets, 1.0);
        assertEquals(new BigDecimal("3"), safe.get("a"));
        assertFalse(safe.containsKey("zero"));
        assertFalse(safe.containsKey("negative"));
        assertFalse(safe.containsKey("null"));
        assertFalse(safe.containsKey(" "));
        assertTrue(AdaptiveTaskPolicy.scaleTargets(null, 1.0).isEmpty());
        assertTrue(AdaptiveTaskPolicy.scaleTargets(Map.of(), 1.0).isEmpty());
    }

    @Test
    void scaleTargetsDoesNotModifyOriginalsAndReturnsImmutableMap() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("a", new BigDecimal("1"));
        targets.put("b", new BigDecimal("2.5"));
        Map<String, BigDecimal> copy = new LinkedHashMap<>(targets);
        Map<String, BigDecimal> scaled = AdaptiveTaskPolicy.scaleTargets(targets, 1.1);
        assertEquals(copy, targets);
        assertEquals(2, scaled.size());
        assertThrows(UnsupportedOperationException.class, () -> scaled.put("c", BigDecimal.ONE));
    }

    @Test
    void stalledNeedsThreeDistinctFailuresBelowQuarterProgress() {
        Map<String, Double> low = Map.of("iron", 0.1);
        assertTrue(AdaptiveTaskPolicy.stalledResources(List.of(failure("a", low), failure("b", low)), DAILY).isEmpty());
        assertEquals(Set.of("iron"), AdaptiveTaskPolicy.stalledResources(
                List.of(failure("a", low), failure("b", low), failure("c", low)), DAILY));
        Map<String, Double> enough = Map.of("iron", 0.3);
        assertTrue(AdaptiveTaskPolicy.stalledResources(
                List.of(failure("a", low), failure("b", low), failure("c", enough)), DAILY).isEmpty());
        assertTrue(AdaptiveTaskPolicy.stalledResources(
                List.of(failure("a", Map.of("iron", 0.25)), failure("b", Map.of("iron", 0.25)),
                        failure("c", Map.of("iron", 0.25))), DAILY).isEmpty());
        assertTrue(AdaptiveTaskPolicy.stalledResources(
                List.of(failure("a", Map.of("iron", 0.24)), failure("b", Map.of("iron", 0.249)),
                        failure("c", Map.of("iron", 0.0))), DAILY).contains("iron"));
    }

    @Test
    void stalledClearsAfterFullSuccessAndRearmsAfterThreeNewFailures() {
        Map<String, Double> low = Map.of("iron", 0.1);
        List<AdaptiveTaskPolicy.Observation> history = new ArrayList<>(List.of(
                failure("a", low), failure("b", low), failure("c", low)));
        assertEquals(Set.of("iron"), AdaptiveTaskPolicy.stalledResources(history, DAILY));

        history.add(observe("d", DAILY, true, 1.0, 0.4, 3, 5, Map.of("iron", 1.0)));
        assertTrue(AdaptiveTaskPolicy.stalledResources(history, DAILY).isEmpty());

        history.add(failure("e", low));
        history.add(failure("f", low));
        assertTrue(AdaptiveTaskPolicy.stalledResources(history, DAILY).isEmpty());

        history.add(failure("g", low));
        assertEquals(Set.of("iron"), AdaptiveTaskPolicy.stalledResources(history, DAILY));
    }

    @Test
    void stalledFailuresAgeOutOfTheRollingWindow() {
        Map<String, Double> low = Map.of("iron", 0.1);
        List<AdaptiveTaskPolicy.Observation> history = new ArrayList<>(List.of(
                failure("a", low), failure("b", low), failure("c", low)));
        history.add(success("d", 0.3));
        history.add(success("e", 0.3));
        history.add(success("f", 0.3));
        assertEquals(Set.of("iron"), AdaptiveTaskPolicy.stalledResources(history, DAILY));
        history.add(success("g", 0.3));
        assertTrue(AdaptiveTaskPolicy.stalledResources(history, DAILY).isEmpty());
    }

    @Test
    void stalledIgnoresNonFiniteAndMissingProgress() {
        Map<String, Double> nan = Map.of("iron", Double.NaN);
        assertTrue(AdaptiveTaskPolicy.stalledResources(
                List.of(failure("a", nan), failure("b", nan), failure("c", nan)), DAILY).isEmpty());
        assertEquals(Set.of("gold"), AdaptiveTaskPolicy.stalledResources(List.of(
                failure("a", Map.of("gold", -4.0)),
                failure("b", Map.of("gold", 0.1, "iron", 0.1)),
                failure("c", Map.of("gold", 0.2))), DAILY));
    }

    @Test
    void stalledResourcesSetIsImmutableAndCadenceScoped() {
        Map<String, Double> low = Map.of("iron", 0.1);
        List<AdaptiveTaskPolicy.Observation> history = List.of(
                failure("a", low), failure("b", low), failure("c", low));
        Set<String> stalled = AdaptiveTaskPolicy.stalledResources(history, DAILY);
        assertThrows(UnsupportedOperationException.class, () -> stalled.add("gold"));
        assertTrue(AdaptiveTaskPolicy.stalledResources(history, WEEKLY).isEmpty());
    }
}