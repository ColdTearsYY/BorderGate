package dev.bordergate.core;

public final class BorderMath {
    private BorderMath() {}

    public static double nextDiameter(double current, double minimum, double maximum,
                                      double expandBy, double shrinkBy, boolean goalMet) {
        if (!Double.isFinite(current) || !Double.isFinite(minimum) || !Double.isFinite(maximum)
                || minimum <= 0 || maximum < minimum) {
            throw new IllegalArgumentException("Invalid border bounds");
        }
        double delta = goalMet ? Math.max(0, expandBy) : -Math.max(0, shrinkBy);
        return Math.max(minimum, Math.min(maximum, current + delta));
    }

    public record Radii(double x, double z) {}

    public static Radii scaleRadii(double radiusX, double radiusZ, double targetDiameter) {
        if (!Double.isFinite(radiusX) || !Double.isFinite(radiusZ) || !Double.isFinite(targetDiameter)
                || radiusX < 0 || radiusZ < 0 || Math.max(radiusX, radiusZ) <= 0 || targetDiameter <= 0) {
            throw new IllegalArgumentException("Invalid border radii or diameter");
        }
        double longest = Math.max(radiusX, radiusZ);
        double targetRadius = targetDiameter / 2;
        return new Radii(radiusX / longest * targetRadius, radiusZ / longest * targetRadius);
    }
}
