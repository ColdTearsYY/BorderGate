package dev.bordergate.core;

/** Deterministic bounded feedback. Sizes are diameters; no world access. */
public final class BorderBalancePolicy {
    public record Adjustment(double expand, double shrink) {}
    private BorderBalancePolicy() {}

    public static Adjustment adjustments(double size, double minimum, double maximum, String cadence,
                                         double dailyPercent, double weeklyPercent) {
        bounds(minimum, maximum);
        if (!Double.isFinite(size) || size <= 0) throw new IllegalArgumentException("Invalid border size");
        double percent = switch (cadence) {
            case "daily" -> dailyPercent;
            case "weekly" -> weeklyPercent;
            default -> throw new IllegalArgumentException("Only daily/weekly adjust diameter");
        };
        percentage(percent);
        double range = maximum - minimum;
        if (range == 0) return new Adjustment(0, 0);
        double position = Math.max(0, Math.min(1, (size - minimum) / range));
        double curve = position * position * (3 - 2 * position);
        double budget = range * (percent / 100);
        return new Adjustment(budget * (1 - curve), budget * curve);
    }

    public static double nextMaximum(double minimum, double maximum, double percent, boolean success, double hardMaximum) {
        bounds(minimum, maximum);
        percentage(percent);
        if (!Double.isFinite(hardMaximum) || hardMaximum < minimum) throw new IllegalArgumentException("Invalid backend maximum");
        double next = maximum * (success ? 1 + percent / 100 : 1 - percent / 100);
        return Math.max(minimum, Math.min(hardMaximum, next));
    }

    private static void bounds(double minimum, double maximum) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum <= 0 || maximum < minimum) {
            throw new IllegalArgumentException("Border bounds must be finite, positive and ordered");
        }
    }
    private static void percentage(double percent) {
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Percentage must be between 0 and 100");
        }
    }
}
