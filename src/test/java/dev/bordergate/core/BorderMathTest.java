package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BorderMathTest {
    @Test void expandsAndClampsAtMaximum() {
        assertEquals(1000.0, BorderMath.nextDiameter(950, 500, 1000, 100, 50, true));
    }
    @Test void contractsAndClampsAtMinimum() {
        assertEquals(500.0, BorderMath.nextDiameter(520, 500, 1000, 100, 50, false));
    }
    @Test void leavesSizeStableWhenChangeIsZero() {
        assertEquals(750.0, BorderMath.nextDiameter(750, 500, 1000, 0, 0, true));
    }
    @Test void rejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> BorderMath.nextDiameter(10, 20, 10, 1, 1, true));
    }
    @Test void scalesBothRadiiProportionallyToTheRequestedDiameter() {
        BorderMath.Radii scaled = BorderMath.scaleRadii(100, 50, 600);
        assertEquals(300.0, scaled.x());
        assertEquals(150.0, scaled.z());
        assertEquals(600.0, Math.max(scaled.x(), scaled.z()) * 2);
    }

    @Test void rejectsInvalidChunkyBorderRadii() {
        assertThrows(IllegalArgumentException.class, () -> BorderMath.scaleRadii(0, 0, 500));
    }

    @Test void scalesWhenZIsTheLongerAxis() {
        BorderMath.Radii scaled = BorderMath.scaleRadii(200, 400, 1000);
        assertEquals(250.0, scaled.x());
        assertEquals(500.0, scaled.z());
    }

    @Test void retainsEqualRadiiForSquaresAndCircles() {
        assertEquals(new BorderMath.Radii(1500, 1500), BorderMath.scaleRadii(500, 500, 3000));
    }

    @Test void rejectsNonFiniteAndNegativeGeometry() {
        assertThrows(IllegalArgumentException.class, () -> BorderMath.scaleRadii(-1, 100, 500));
        assertThrows(IllegalArgumentException.class, () -> BorderMath.scaleRadii(Double.NaN, 100, 500));
        assertThrows(IllegalArgumentException.class, () -> BorderMath.scaleRadii(100, 100, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> BorderMath.scaleRadii(100, 100, 0));
    }

    @Test void pinsTheLongestRadiusToTheExactTarget() {
        BorderMath.Radii scaled = BorderMath.scaleRadii(3564, 1782, 7256);
        assertEquals(3628.0, scaled.x());
        assertEquals(1814.0, scaled.z());
    }
}
