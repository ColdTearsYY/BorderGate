package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuestSettlementPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "999,false,false,PENDING", "999,true,false,PENDING",
        "1000,false,false,FAILURE", "1000,true,false,SUCCESS",
        "1001,false,false,FAILURE", "1001,true,false,SUCCESS",
        "999,false,true,PENDING", "999,true,true,SUCCESS",
        "1000,false,true,FAILURE", "1000,true,true,SUCCESS",
        "1001,false,true,FAILURE", "1001,true,true,SUCCESS"
    })
    void handlesCompletionBeforeAtAndAfterDeadline(long now, boolean complete, boolean early, String outcome) {
        assertEquals(QuestSettlementPolicy.Outcome.valueOf(outcome),
                QuestSettlementPolicy.evaluate(now, 1000, complete, early));
    }

    @Test void earlyCompletedDailyTaskWaitsAndStillSucceedsAtBeijingFour() {
        TaskSchedule schedule = new TaskSchedule("Asia/Shanghai", 4);
        long now = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli();
        long deadline = schedule.nextBoundary("daily", now);
        QuestCycle cycle = new QuestCycle("daily-test", "daily", "daily", Map.of("item:DIAMOND", BigDecimal.TEN), 1, BigDecimal.ONE);
        assertTrue(cycle.contribute(UUID.randomUUID(), "item:DIAMOND", BigDecimal.TEN, BigDecimal.ONE));
        assertTrue(cycle.isComplete());
        assertEquals(QuestSettlementPolicy.Outcome.PENDING, QuestSettlementPolicy.evaluate(now, deadline, cycle.isComplete(), false));
        assertEquals(QuestSettlementPolicy.Outcome.PENDING, QuestSettlementPolicy.evaluate(deadline - 1, deadline, cycle.isComplete(), false));
        assertEquals(QuestSettlementPolicy.Outcome.SUCCESS, QuestSettlementPolicy.evaluate(deadline, deadline, cycle.isComplete(), false));
        assertEquals(2100.0, BorderMath.nextDiameter(2000, 1000, 20000, 100, 10,
                QuestSettlementPolicy.evaluate(deadline, deadline, cycle.isComplete(), false) == QuestSettlementPolicy.Outcome.SUCCESS));
    }

    @Test void incompleteDailyTaskShrinksOnlyAtDeadline() {
        assertEquals(QuestSettlementPolicy.Outcome.PENDING, QuestSettlementPolicy.evaluate(999, 1000, false, false));
        QuestSettlementPolicy.Outcome result = QuestSettlementPolicy.evaluate(1000, 1000, false, false);
        assertEquals(QuestSettlementPolicy.Outcome.FAILURE, result);
        assertEquals(1990.0, BorderMath.nextDiameter(2000, 1000, 20000, 100, 10, result == QuestSettlementPolicy.Outcome.SUCCESS));
    }

    @Test void completedWeeklyTaskWaitsForMondayFourAndNextPeriodDoesNotStartEarly() {
        TaskSchedule schedule = new TaskSchedule("Asia/Shanghai", 4);
        long now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli();
        long deadline = schedule.nextBoundary("weekly", now);
        assertEquals(Instant.parse("2026-11-15T20:00:00Z").toEpochMilli(), deadline);
        assertEquals(QuestSettlementPolicy.Outcome.PENDING, QuestSettlementPolicy.evaluate(now, deadline, true, false));
        assertEquals(QuestSettlementPolicy.Outcome.SUCCESS, QuestSettlementPolicy.evaluate(deadline, deadline, true, false));
        assertEquals(schedule.periodDate("weekly", now), schedule.periodDate("weekly", deadline - 1));
        assertNotEquals(schedule.periodDate("weekly", now), schedule.periodDate("weekly", deadline));
    }
}
