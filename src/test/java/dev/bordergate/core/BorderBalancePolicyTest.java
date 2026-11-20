package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BorderBalancePolicyTest {
    private BorderBalancePolicy.Adjustment daily(double size) {
        return BorderBalancePolicy.adjustments(size, 1000, 12000, "daily", 1, 4);
    }
    @Test void lowerBoundMaximizesExpansionAndRemovesPenalty() {
        assertEquals(110, daily(1000).expand(), 1e-9);
        assertEquals(0, daily(1000).shrink(), 1e-9);
    }
    @Test void upperBoundRemovesExpansionAndMaximizesPenalty() {
        assertEquals(0, daily(12000).expand(), 1e-9);
        assertEquals(110, daily(12000).shrink(), 1e-9);
    }
    @Test void midpointIsBalanced() {
        assertEquals(55, daily(6500).expand(), 1e-9);
        assertEquals(55, daily(6500).shrink(), 1e-9);
    }
    @Test void curveIsMonotoneAndNeverNegative() {
        double expansion = Double.MAX_VALUE, penalty = -1;
        for (int size = 900; size <= 12100; size += 5) {
            var value = daily(size);
            assertTrue(value.expand() >= 0 && value.expand() <= expansion + 1e-9);
            assertTrue(value.shrink() >= penalty - 1e-9 && value.shrink() <= 110 + 1e-9);
            assertEquals(110, value.expand() + value.shrink(), 1e-9);
            expansion = value.expand(); penalty = value.shrink();
        }
    }
    @Test void weeklyHasLargerBudget() {
        var weekly = BorderBalancePolicy.adjustments(6500, 1000, 12000, "weekly", 1, 4);
        assertEquals(220, weekly.expand(), 1e-9);
        assertEquals(220, weekly.shrink(), 1e-9);
    }
    @Test void equalBoundsHaveNoMovement() {
        assertEquals(new BorderBalancePolicy.Adjustment(0, 0), BorderBalancePolicy.adjustments(1000, 1000, 1000, "daily", 1, 4));
    }
    @Test void monthlyUsesCurrentMaximumAndFivePercentDefault() {
        assertEquals(12600, BorderBalancePolicy.nextMaximum(1000, 12000, 5, true, 60000000), 1e-9);
        assertEquals(11400, BorderBalancePolicy.nextMaximum(1000, 12000, 5, false, 60000000), 1e-9);
    }
    @Test void monthlyFailureCannotCrossMinimumOrBackendMaximum() {
        assertEquals(1000, BorderBalancePolicy.nextMaximum(1000, 1020, 5, false, 60000000), 1e-9);
        assertEquals(60000000, BorderBalancePolicy.nextMaximum(1000, 59000000, 5, true, 60000000), 1e-9);
    }
    @Test void configurableMonthlyPercentageCanBeZero() {
        assertEquals(12000, BorderBalancePolicy.nextMaximum(1000, 12000, 0, false, 60000000));
        assertEquals(12120, BorderBalancePolicy.nextMaximum(1000, 12000, 1, true, 60000000), 1e-9);
    }
    @Test void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> daily(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BorderBalancePolicy.adjustments(1000, 12000, 1000, "daily", 1, 4));
        assertThrows(IllegalArgumentException.class, () -> BorderBalancePolicy.adjustments(1000, 1000, 12000, "monthly", 1, 4));
        assertThrows(IllegalArgumentException.class, () -> BorderBalancePolicy.nextMaximum(1000, 12000, Double.NaN, true, 60000000));
        assertThrows(IllegalArgumentException.class, () -> BorderBalancePolicy.nextMaximum(1000, 12000, 101, false, 60000000));
    }
}
