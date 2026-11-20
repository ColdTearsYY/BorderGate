package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SettlementAnnouncementsTest {
    private final SettlementAnnouncements announcements = new SettlementAnnouncements();
    private final List<String> messages = new ArrayList<>();
    private boolean send(String taskId, String cadence, boolean success, double size) {
        return announcements.announce(true, "lifetravel", "lifetravel", taskId, cadence, success, size,
                SettlementAnnouncements.DEFAULT_SUCCESS, SettlementAnnouncements.DEFAULT_FAILURE, messages::add);
    }

    @Test void rendersSuccessfulDailySettlementWithFinalDiameter() {
        assertTrue(send("lifetravel:daily-2026-11-18", "daily", true, 2100));
        assertEquals(List.of("&a[边界维护] 每日任务已达标，本区边界直径：2100 格。"), messages);
    }

    @Test void rendersFailedWeeklySettlement() {
        assertTrue(send("lifetravel:weekly-2026-11-16", "weekly", false, 1930));
        assertEquals(List.of("&e[边界维护] 每周任务未达标，本区边界直径：1930 格。"), messages);
    }

    @Test void disabledAnnouncementDoesNotConsumeTaskIdentity() {
        assertFalse(announcements.announce(false, "lifetravel", "lifetravel", "task", "daily", true, 2100,
                SettlementAnnouncements.DEFAULT_SUCCESS, SettlementAnnouncements.DEFAULT_FAILURE, messages::add));
        assertTrue(messages.isEmpty());
        assertTrue(send("task", "daily", true, 2100));
    }

    @Test void foreignOwnerIsNeverBroadcastLocally() {
        assertFalse(announcements.announce(true, "lifetravel", "newtravel", "newtravel:daily-2026-11-18", "daily", true, 2100,
                SettlementAnnouncements.DEFAULT_SUCCESS, SettlementAnnouncements.DEFAULT_FAILURE, messages::add));
        assertTrue(messages.isEmpty());
    }

    @Test void repeatedSettlementEvenWithDifferentOutcomeIsNotRepeated() {
        assertTrue(send("task", "daily", true, 2100));
        assertFalse(send("task", "daily", true, 2100));
        assertFalse(send("task", "daily", false, 1990));
        assertEquals(1, messages.size());
    }

    @Test void separateTaskIdentitiesAreAnnouncedSeparately() {
        assertTrue(send("lifetravel:daily-2026-11-18", "daily", true, 2100));
        assertTrue(send("lifetravel:weekly-2026-11-16", "weekly", true, 2800));
        assertEquals(2, messages.size());
    }

    @Test void blankSelectedTemplateIsNotBroadcast() {
        assertFalse(announcements.announce(true, "lifetravel", "lifetravel", "task", "daily", true, 2100,
                " ", SettlementAnnouncements.DEFAULT_FAILURE, messages::add));
        assertTrue(messages.isEmpty());
        assertTrue(send("task", "daily", true, 2100));
    }

    @Test void dispatcherFailureDoesNotMarkAnnouncementAsSuccessful() {
        assertThrows(IllegalStateException.class, () -> announcements.announce(true, "lifetravel", "lifetravel", "task", "daily", true, 2100,
                SettlementAnnouncements.DEFAULT_SUCCESS, SettlementAnnouncements.DEFAULT_FAILURE, message -> { throw new IllegalStateException("dispatch unavailable"); }));
        assertTrue(send("task", "daily", true, 2100));
        assertFalse(send("task", "daily", true, 2100));
    }

    @Test void invalidDiameterIsNotBroadcast() {
        for (double size : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(send("task", "daily", true, size));
        }
        assertTrue(messages.isEmpty());
    }

    @Test void customTemplateExpandsIdentityCadenceAndFractionalSize() {
        assertTrue(announcements.announce(true, "lifetravel", "lifetravel", "lifetravel:daily-2026-11-18", "daily", true, 2100.5,
                "{task} | {cadence} | {size}", "failure", messages::add));
        assertEquals("lifetravel:daily-2026-11-18 | 每日任务 | 2100.5", messages.getFirst());
    }

    @Test void concurrentChecksDispatchExactlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return announcements.announce(true, "lifetravel", "lifetravel", "task", "daily", true, 2100,
                        SettlementAnnouncements.DEFAULT_SUCCESS, SettlementAnnouncements.DEFAULT_FAILURE, message -> dispatches.incrementAndGet());
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(1, dispatches.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test void maximumDiameterReportsActualValueWithoutInventingFurtherExpansion() {
        assertTrue(send("task", "daily", true, 20000));
        assertEquals("&a[边界维护] 每日任务已达标，本区边界直径：20000 格。", messages.getFirst());
    }

    @Test void rendersMonthlySettlementLabelWithoutChangingDeduplication() {
        assertTrue(send("lifetravel:monthly-2026-11", "monthly", true, 2100));
        assertEquals(List.of("&a[边界维护] 每月任务已达标，本区边界直径：2100 格。"), messages);
        assertFalse(send("lifetravel:monthly-2026-11", "monthly", false, 2100));
        assertEquals(1, messages.size());
    }
}
