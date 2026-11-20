package dev.bordergate.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** Calendar periods anchored to a configured local rollover, independent of the JVM timezone. */
public final class TaskSchedule {
    public static final List<String> CADENCES = List.of("daily", "weekly", "monthly");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final ZoneId zone;
    private final LocalTime rollover;

    public TaskSchedule(String timezone, int rolloverHour) {
        if (rolloverHour < 0 || rolloverHour > 23) {
            throw new IllegalArgumentException("schedule.rollover-hour must be between 0 and 23");
        }
        zone = ZoneId.of(timezone);
        rollover = LocalTime.of(rolloverHour, 0);
    }

    public LocalDate periodDate(String cadence, long epochMillis) {
        requireCadence(cadence);
        ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(zone);
        LocalDate date = now.toLocalDate();
        if (now.toInstant().isBefore(date.atTime(rollover).atZone(zone).toInstant())) {
            date = date.minusDays(1);
        }
        return switch (cadence) {
            case "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "monthly" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    public long nextBoundary(String cadence, long epochMillis) {
        requireCadence(cadence);
        LocalDate start = periodDate(cadence, epochMillis);
        LocalDate next = switch (cadence) {
            case "weekly" -> start.plusWeeks(1);
            case "monthly" -> start.plusMonths(1);
            default -> start.plusDays(1);
        };
        return next.atTime(rollover).atZone(zone).toInstant().toEpochMilli();
    }

    public String formatDeadline(long epochMillis) {
        return DISPLAY.withZone(zone).format(Instant.ofEpochMilli(epochMillis));
    }

    private static void requireCadence(String cadence) {
        if (!CADENCES.contains(cadence)) {
            throw new IllegalArgumentException("Unknown task cadence: " + cadence);
        }
    }
}
