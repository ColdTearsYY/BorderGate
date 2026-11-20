package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuestCycleTest {
    @Test
    void completesWhenConfiguredNumberOfResourcesAreMet() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("item:DIAMOND", new BigDecimal("10"));
        targets.put("vault", new BigDecimal("100"));
        targets.put("playerpoints", new BigDecimal("20"));
        QuestCycle cycle = new QuestCycle("daily-1", "Daily", "daily", targets, 2, BigDecimal.ONE);
        UUID player = UUID.randomUUID();
        assertTrue(cycle.contribute(player, "item:DIAMOND", new BigDecimal("10"), BigDecimal.ONE));
        assertTrue(cycle.contribute(player, "vault", new BigDecimal("100"), BigDecimal.ONE));
        assertTrue(cycle.isComplete());
        assertEquals(new BigDecimal("100"), cycle.completionPercent());
    }

    @Test
    void calculatesScoreWeightedRewardShares() {
        QuestCycle cycle = new QuestCycle("weekly-1", "Weekly", "weekly",
                Map.of("item:DIAMOND", new BigDecimal("100")), 1, new BigDecimal("3"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(cycle.contribute(first, "item:DIAMOND", new BigDecimal("25"), BigDecimal.ONE));
        assertTrue(cycle.contribute(second, "item:DIAMOND", new BigDecimal("75"), BigDecimal.ONE));
        assertEquals(0, cycle.playerPercent(first).compareTo(new BigDecimal("25.00")));
        Map<UUID, BigDecimal> shares = cycle.rewardShares(new BigDecimal("1000"));
        assertEquals(new BigDecimal("250.00"), shares.get(first));
        assertEquals(new BigDecimal("750.00"), shares.get(second));
    }

    @Test
    void fixedModePaysTheConfiguredBaseToEveryParticipant() {
        QuestCycle cycle = cycleWithScores();
        Map<UUID, BigDecimal> shares = cycle.rewardShares(new BigDecimal("10"), "fixed", 0);
        assertEquals(new BigDecimal("10"), shares.values().stream().findFirst().orElseThrow());
        assertEquals(2, shares.size());
        assertEquals(new BigDecimal("20"), cycle.totalReward(new BigDecimal("10"), "fixed", 0));
    }

    @Test
    void poolModeMultipliesBaseByParticipantsBeforeSplitting() {
        QuestCycle cycle = cycleWithScores();
        Map<UUID, BigDecimal> shares = cycle.rewardShares(new BigDecimal("10"), "pool", 0);
        assertEquals(new BigDecimal("13"), shares.values().stream().max(BigDecimal::compareTo).orElseThrow());
        assertEquals(new BigDecimal("7"), shares.values().stream().min(BigDecimal::compareTo).orElseThrow());
        assertEquals(new BigDecimal("20"), shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(new BigDecimal("20"), cycle.totalReward(new BigDecimal("10"), "pool", 0));
    }

    @Test
    void rejectsOverTargetContribution() {
        QuestCycle cycle = new QuestCycle("daily-1", "Daily", "daily",
                Map.of("item:DIAMOND", new BigDecimal("3")), 1, BigDecimal.ONE);
        assertFalse(cycle.contribute(UUID.randomUUID(), "item:DIAMOND", new BigDecimal("4"), BigDecimal.ONE));
        assertEquals(BigDecimal.ZERO, cycle.remaining("item:DIAMOND").subtract(new BigDecimal("3")));
    }

    @Test
    void restoresCrossServerAggregatesForRankingAndRewards() {
        QuestCycle cycle = new QuestCycle("server-a:daily-1", "Daily", "daily",
                Map.of("item:DIAMOND", new BigDecimal("10")), 1, BigDecimal.ONE);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cycle.restoreAggregates(Map.of("item:DIAMOND", new BigDecimal("10")),
                Map.of(first, new BigDecimal("40"), second, new BigDecimal("60")));

        assertTrue(cycle.isComplete());
        assertEquals(0, cycle.playerPercent(first).compareTo(new BigDecimal("40")));
        assertEquals(new BigDecimal("40.00"), cycle.rewardShares(new BigDecimal("100")).get(first));
    }

    private QuestCycle cycleWithScores() {
        QuestCycle cycle = new QuestCycle("daily-reward", "Reward", "daily",
                Map.of("item:DIAMOND", new BigDecimal("100")), 1, BigDecimal.ONE);
        assertTrue(cycle.contribute(UUID.fromString("00000000-0000-0000-0000-000000000001"), "item:DIAMOND", new BigDecimal("65"), BigDecimal.ONE));
        assertTrue(cycle.contribute(UUID.fromString("00000000-0000-0000-0000-000000000002"), "item:DIAMOND", new BigDecimal("35"), BigDecimal.ONE));
        return cycle;
    }
}
