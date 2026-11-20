package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskStatusFormatterTest {
    private final TaskSchedule schedule = new TaskSchedule("Asia/Shanghai", 4);
    private static long local(String value) {
        return ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]").toInstant().toEpochMilli();
    }
    private QuestCycle daily() {
        return new QuestCycle("daily-2026-11-17", "每日测试", "daily",
                Map.of("item:DIAMOND", BigDecimal.TEN), 1, BigDecimal.ONE);
    }
    private String render(QuestCycle cycle, long deadline, boolean settled, long now) {
        return String.join("\n", TaskStatusFormatter.lines("daily", cycle, deadline, settled, schedule, now));
    }

    @Test void rendersActualDailyDeadlineAndCountdown() {
        QuestCycle cycle = daily();
        assertTrue(cycle.contribute(UUID.randomUUID(), "item:DIAMOND", new BigDecimal("4"), BigDecimal.ONE));
        String output = render(cycle, local("2026-11-18T04:00:00"), false, local("2026-11-17T21:29:45"));
        assertTrue(output.contains("[每日任务] 每日测试 | 任务ID: daily-2026-11-17"));
        assertTrue(output.contains("截止时间: 11-18 04:00 | 剩余: 6小时30分钟15秒"));
        assertTrue(output.contains("状态: 进行中"));
        assertTrue(output.contains("item:DIAMOND: 4/10"));
    }

    @Test void weeklyCountdownKeepsAllHoursInsteadOfWrappingAtADay() {
        QuestCycle cycle = new QuestCycle("weekly-2026-11-16", "每周测试", "weekly",
                Map.of("vault", BigDecimal.TEN), 1, BigDecimal.ONE);
        String output = String.join("\n", TaskStatusFormatter.lines("weekly", cycle, local("2026-11-23T04:00:00"), false,
                schedule, local("2026-11-16T04:00:00")));
        assertTrue(output.contains("[每周任务]"));
        assertTrue(output.contains("截止时间: 11-23 04:00 | 剩余: 168小时0分钟0秒"));
    }

    @Test void keepsPersistedDeadlineAfterScheduleChanges() {
        long now = local("2026-11-18T12:00:00");
        long oldDeadline = local("2026-11-19T08:00:00");
        assertNotEquals(oldDeadline, schedule.nextBoundary("daily", now));
        String output = render(daily(), oldDeadline, false, now);
        assertTrue(output.contains("截止时间: 11-19 08:00 | 剩余: 20小时0分钟0秒"));
        assertFalse(output.contains("11-19 04:00"));
    }

    @Test void missingQuestDoesNotShowLegacyTargetsOrEpochDeadline() {
        assertEquals(List.of("[每日任务] 暂无可用任务。"),
                TaskStatusFormatter.lines("daily", null, 0, false, schedule, local("2026-11-18T12:00:00")));
        assertEquals(List.of("[每周任务] 暂无可用任务。"),
                TaskStatusFormatter.lines("weekly", null, 0, false, schedule, local("2026-11-18T12:00:00")));
    }

    @Test void expiredQuestHasNoNegativeCountdown() {
        String output = render(daily(), local("2026-11-18T04:00:00"), false, local("2026-11-18T04:01:00"));
        assertTrue(output.contains("状态: 已到期，等待结算"));
        assertTrue(output.contains("截止时间: 11-18 04:00 | 已到期"));
        assertFalse(output.contains("剩余:"));
    }

    @Test void completedQuestWaitingForSettlementIsNotReportedAsActive() {
        QuestCycle cycle = daily();
        cycle.restoreAggregates(cycle.targets(), Map.of());
        String output = render(cycle, local("2026-11-18T04:00:00"), false, local("2026-11-17T12:00:00"));
        assertTrue(output.contains("状态: 已完成，等待结算"));
        assertTrue(output.contains("完成率: 100%"));
    }

    @Test void settledQuestDoesNotAdvertiseAnotherSettlementCountdown() {
        String output = render(daily(), local("2026-11-18T04:00:00"), true, local("2026-11-17T12:00:00"));
        assertTrue(output.contains("状态: 已结算"));
        assertTrue(output.contains("本期已结算"));
        assertFalse(output.contains("剩余:"));
    }

    @Test void displaysRequiredKindsAndAllResourceTotalsWithoutMutation() {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put("item:DIAMOND", BigDecimal.TEN);
        targets.put("vault", new BigDecimal("100"));
        targets.put("playerpoints", new BigDecimal("20"));
        QuestCycle cycle = new QuestCycle("daily-multi", "多目标", "daily", targets, 2, BigDecimal.ONE);
        cycle.restoreAggregates(Map.of("item:DIAMOND", BigDecimal.TEN, "vault", new BigDecimal("100")), Map.of());
        Map<String, BigDecimal> before = Map.copyOf(cycle.contributed());
        String output = render(cycle, local("2026-11-18T04:00:00"), false, local("2026-11-17T12:00:00"));
        assertTrue(output.contains("目标达成: 2/3种（需2种） | 完成率: 100%"));
        assertTrue(output.contains("playerpoints: 0/20"));
        assertEquals(before, cycle.contributed());
    }

    @Test void unknownDeadlineIsNotFormattedAsEpochOrExpired() {
        String output = render(daily(), 0, false, local("2026-11-18T12:00:00"));
        assertTrue(output.contains("截止时间: 未设置"));
        assertFalse(output.contains("已到期"));
        assertFalse(output.contains("01-01"));
    }

    @Test void usesTheConfiguredScheduleToFormatTheDeadline() {
        long end = local("2026-11-18T04:00:00");
        String output = String.join("\n", TaskStatusFormatter.lines("daily", daily(), end, false,
                new TaskSchedule("UTC", 0), local("2026-11-17T12:00:00")));
        assertTrue(output.contains("截止时间: 11-17 20:00"));
        assertFalse(output.contains("11-18 04:00"));
    }

    @Test void monthlyLabelPreservesPersistedDeadlineAndReadOnlyView() {
        QuestCycle cycle = new QuestCycle("monthly-2026-11", "每月测试", "monthly",
                Map.of("vault", BigDecimal.TEN), 1, BigDecimal.ONE);
        long deadline = local("2026-12-01T04:00:00");
        String output = String.join("\n", TaskStatusFormatter.lines("monthly", cycle, deadline, false,
                schedule, local("2026-11-18T12:00:00")));
        assertTrue(output.contains("[每月任务] 每月测试"));
        assertTrue(output.contains("截止时间: 12-01 04:00"));
        assertTrue(output.contains("vault: 0/10"));
        assertEquals(BigDecimal.ZERO, cycle.contributed().getOrDefault("vault", BigDecimal.ZERO));
    }
}
