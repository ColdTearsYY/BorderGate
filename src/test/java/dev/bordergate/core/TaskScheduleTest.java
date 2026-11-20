package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaskScheduleTest {
    private final TaskSchedule schedule = new TaskSchedule("Asia/Shanghai", 4);
    private static long local(String value) {
        return ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]").toInstant().toEpochMilli();
    }

    @ParameterizedTest
    @CsvSource({
        "daily,2026-11-18T03:59:59.999,2026-11-17,2026-11-18T04:00:00",
        "daily,2026-11-18T04:00:00,2026-11-18,2026-11-19T04:00:00",
        "daily,2026-11-18T04:00:00.001,2026-11-18,2026-11-19T04:00:00",
        "daily,2026-11-18T00:00:00,2026-11-17,2026-11-18T04:00:00",
        "daily,2027-01-01T03:59:59,2026-12-31,2027-01-01T04:00:00",
        "daily,2027-01-01T04:00:00,2027-01-01,2027-01-02T04:00:00",
        "daily,2028-03-01T03:59:59,2028-02-29,2028-03-01T04:00:00",
        "weekly,2026-11-16T03:59:59.999,2026-11-09,2026-11-16T04:00:00",
        "weekly,2026-11-16T04:00:00,2026-11-16,2026-11-23T04:00:00",
        "weekly,2026-11-16T04:00:00.001,2026-11-16,2026-11-23T04:00:00",
        "weekly,2026-11-15T23:59:59,2026-11-09,2026-11-16T04:00:00",
        "weekly,2026-11-16T00:00:00,2026-11-09,2026-11-16T04:00:00",
        "weekly,2026-11-22T23:59:59,2026-11-16,2026-11-23T04:00:00",
        "weekly,2026-11-01T03:59:59,2026-10-26,2026-11-02T04:00:00",
        "weekly,2024-01-01T03:59:59,2023-12-25,2024-01-01T04:00:00",
        "weekly,2024-01-01T04:00:00,2024-01-01,2024-01-08T04:00:00"
    })
    void identifiesPeriodAndNextStrictlyFutureBoundary(String cadence, String now, String period, String next) {
        long millis = local(now);
        assertEquals(LocalDate.parse(period), schedule.periodDate(cadence, millis));
        assertEquals(local(next), schedule.nextBoundary(cadence, millis));
        assertTrue(schedule.nextBoundary(cadence, millis) > millis);
    }

    @Test void convertsBeijingFourToPreviousUtcEvening() {
        assertEquals(Instant.parse("2026-11-17T20:00:00Z").toEpochMilli(),
                schedule.nextBoundary("daily", Instant.parse("2026-11-17T19:59:59Z").toEpochMilli()));
    }

    @Test void keepsLegacyUtcMidnightWhenConfigured() {
        TaskSchedule legacy = new TaskSchedule("UTC", 0);
        long now = Instant.parse("2026-11-16T00:00:00Z").toEpochMilli();
        assertEquals(LocalDate.parse("2026-11-16"), legacy.periodDate("weekly", now));
        assertEquals(Instant.parse("2026-11-23T00:00:00Z").toEpochMilli(), legacy.nextBoundary("weekly", now));
    }

    @Test void displaysBeijingTimeRegardlessOfSystemTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals("11-18 04:00", schedule.formatDeadline(Instant.parse("2026-11-17T20:00:00Z").toEpochMilli()));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test void periodsStayConsistentForEveryHourAcrossAYear() {
        long start = local("2026-01-01T00:00:00");
        for (int hour = 0; hour < 366 * 24; hour++) {
            long now = start + hour * 3_600_000L;
            for (String cadence : new String[] {"daily", "weekly"}) {
                LocalDate period = schedule.periodDate(cadence, now);
                long boundary = schedule.nextBoundary(cadence, now);
                assertTrue(boundary > now);
                assertEquals(period, schedule.periodDate(cadence, boundary - 1));
                assertEquals(cadence.equals("weekly") ? period.plusWeeks(1) : period.plusDays(1),
                        schedule.periodDate(cadence, boundary));
                assertEquals(4, Instant.ofEpochMilli(boundary).atZone(ZoneId.of("Asia/Shanghai")).getHour());
            }
        }
    }

    @Test void rejectsInvalidSchedule() {
        assertThrows(IllegalArgumentException.class, () -> new TaskSchedule("Asia/Shanghai", -1));
        assertThrows(IllegalArgumentException.class, () -> new TaskSchedule("Asia/Shanghai", 24));
        assertThrows(java.time.DateTimeException.class, () -> new TaskSchedule("Invalid/Zone", 4));
    }

    @Test void monthlyRolloverUsesCalendarMonthsIncludingLeapDayAndYearChange() {
        assertMonthly("2028-02-01T03:59:59", "2028-01-01", "2028-02-01T04:00:00");
        assertMonthly("2028-02-01T04:00:00", "2028-02-01", "2028-03-01T04:00:00");
        assertMonthly("2028-02-29T12:00:00", "2028-02-01", "2028-03-01T04:00:00");
        assertMonthly("2026-12-31T23:00:00", "2026-12-01", "2027-01-01T04:00:00");
        assertMonthly("2027-01-01T03:59:59", "2026-12-01", "2027-01-01T04:00:00");
    }

    private void assertMonthly(String now, String period, String next) {
        long millis = local(now);
        assertEquals(LocalDate.parse(period), schedule.periodDate("monthly", millis));
        assertEquals(local(next), schedule.nextBoundary("monthly", millis));
        assertTrue(schedule.nextBoundary("monthly", millis) > millis);
    }

    @Test void monthlyBoundaryUsesLocalDstRulesAndRealMonthLength() {
        TaskSchedule newYork = new TaskSchedule("America/New_York", 2);
        long now = Instant.parse("2026-03-10T16:00:00Z").toEpochMilli();
        assertEquals(LocalDate.parse("2026-03-01"), newYork.periodDate("monthly", now));
        assertEquals(Instant.parse("2026-04-01T06:00:00Z").toEpochMilli(),
                newYork.nextBoundary("monthly", now));
    }

    @Test void exposesSupportedCadencesAndRejectsUnknownValues() {
        assertEquals(java.util.List.of("daily", "weekly", "monthly"), TaskSchedule.CADENCES);
        assertThrows(IllegalArgumentException.class, () -> schedule.periodDate("yearly", local("2026-11-18T12:00:00")));
        assertThrows(IllegalArgumentException.class, () -> schedule.nextBoundary("yearly", local("2026-11-18T12:00:00")));
    }
}
