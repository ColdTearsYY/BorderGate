package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaskOverviewTest {
    private QuestCycle cycle() {
        var targets = new LinkedHashMap<String, BigDecimal>();
        targets.put("item:APPLE", new BigDecimal("10"));
        targets.put("vault", new BigDecimal("100.50"));
        return new QuestCycle("server:daily", "每日建造", "daily", targets, 1, BigDecimal.ONE);
    }

    @Test void overviewDoesNotMutateContributionOrScores() {
        QuestCycle cycle = cycle();
        var before = new LinkedHashMap<>(cycle.contributed());
        var scores = new LinkedHashMap<>(cycle.scores());
        TaskOverview view = TaskOverview.snapshot("daily", cycle, 2000, false, 1000);
        assertEquals(before, cycle.contributed());
        assertEquals(scores, cycle.scores());
        assertEquals(new BigDecimal("10"), view.resources().getFirst().remaining());
        assertEquals("进行中", view.status());
    }

    @Test void existingSnapshotNeverReflectsLaterPayments() {
        QuestCycle cycle = cycle();
        TaskOverview old = TaskOverview.snapshot("daily", cycle, 2000, false, 1000);
        assertTrue(cycle.contribute(UUID.randomUUID(), "item:APPLE", new BigDecimal("10"), BigDecimal.ONE));
        assertEquals(BigDecimal.ZERO, old.resources().getFirst().contributed());
        assertEquals(0, old.completed());
        TaskOverview fresh = TaskOverview.snapshot("daily", cycle, 2000, false, 1000);
        assertEquals(1, fresh.completed());
        assertEquals("已达标，等待结算", fresh.status());
        assertEquals(0, fresh.percent().compareTo(new BigDecimal("100")));
        assertEquals(2, fresh.resources().size());
    }

    @Test void snapshotResourcesAndPagesAreImmutable() {
        TaskOverview view = TaskOverview.snapshot("daily", cycle(), 2000, false, 1000);
        assertThrows(UnsupportedOperationException.class, () -> view.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.page(0).clear());
    }

    @Test void expiryAndSettlementRemainDistinct() {
        assertEquals("已到期，等待结算", TaskOverview.snapshot("daily", cycle(), 1000, false, 1000).status());
        assertEquals("已结算", TaskOverview.snapshot("daily", cycle(), 1000, true, 1000).status());
    }

    @Test void missingPeriodProducesEmptyViewWithoutCreatingTask() {
        TaskOverview view = TaskOverview.snapshot("weekly", null, 0, false, 1000);
        assertEquals("暂无任务", view.status());
        assertEquals("weekly", view.cadence());
        assertEquals(List.of(), view.page(0));
        assertEquals(1, view.pages());
    }

    @Test void fractionalCurrencyAndGapAreExact() {
        QuestCycle cycle = cycle();
        cycle.contribute(UUID.randomUUID(), "vault", new BigDecimal("0.25"), BigDecimal.ONE);
        var resource = TaskOverview.snapshot("daily", cycle, 2000, false, 1000).resources().get(1);
        assertEquals(new BigDecimal("100.25"), resource.remaining());
    }

    @Test void paginationDoesNotLoseOrDuplicateResources() {
        var targets = new LinkedHashMap<String, BigDecimal>();
        for (int i = 0; i < 25; i++) targets.put("resource:" + i, BigDecimal.TEN);
        var cycle = new QuestCycle("large", "large", "monthly", targets, 3, BigDecimal.ONE);
        var view = TaskOverview.snapshot("monthly", cycle, 2000, false, 1000);
        assertEquals(3, view.pages());
        List<String> all = new ArrayList<>();
        for (int page = 0; page < view.pages(); page++) all.addAll(view.page(page).stream().map(TaskOverview.Resource::id).toList());
        assertEquals(List.copyOf(targets.keySet()), all);
        assertTrue(view.page(3).isEmpty());
        assertTrue(view.page(Integer.MAX_VALUE).isEmpty());
        assertEquals(view.page(0), view.page(-1));
    }

    @Test void threeColumnsNeverOverlapHeadersOrControls() {
        Set<Integer> slots = new HashSet<>();
        for (int column = 0; column < 3; column++) for (int index = 0; index < 12; index++) {
            int slot = TaskOverview.resourceSlot(column, index);
            assertTrue(slot >= 9 && slot < 45);
            assertTrue(slots.add(slot));
            assertEquals(column, slot % 9 / 3);
        }
        assertEquals(36, slots.size());
        assertThrows(IllegalArgumentException.class, () -> TaskOverview.resourceSlot(3, 0));
        assertThrows(IllegalArgumentException.class, () -> TaskOverview.resourceSlot(0, 12));
    }
}
