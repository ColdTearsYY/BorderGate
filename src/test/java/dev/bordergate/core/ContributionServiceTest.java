package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ContributionServiceTest {
    @Test
    void failedPaymentDoesNotIncreaseProgress() {
        GoalLedger ledger = GoalLedger.empty(Map.of("vault", new BigDecimal("100")));
        AtomicBoolean attempted = new AtomicBoolean();
        ContributionService.Result result = ContributionService.contribute(ledger, "vault", new BigDecimal("25"), (resource, amount) -> {
            attempted.set(true);
            return false;
        });
        assertTrue(attempted.get());
        assertEquals(ContributionService.Result.PAYMENT_FAILED, result);
        assertEquals(new BigDecimal("100"), ledger.remaining("vault"));
        assertTrue(ledger.contributed().isEmpty());
    }

    @Test
    void oversizedContributionIsRejectedBeforePayment() {
        GoalLedger ledger = GoalLedger.empty(Map.of("item:DIAMOND", new BigDecimal("4")));
        AtomicBoolean attempted = new AtomicBoolean();
        ContributionService.Result result = ContributionService.contribute(ledger, "item:DIAMOND", new BigDecimal("5"), (resource, amount) -> {
            attempted.set(true);
            return true;
        });
        assertEquals(ContributionService.Result.INVALID, result);
        assertFalse(attempted.get());
        assertEquals(new BigDecimal("4"), ledger.remaining("item:DIAMOND"));
    }
}
