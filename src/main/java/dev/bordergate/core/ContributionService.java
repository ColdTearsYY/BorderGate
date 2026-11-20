package dev.bordergate.core;

import java.math.BigDecimal;

public final class ContributionService {
    private ContributionService() {}

    public enum Result { SUCCESS, INVALID, PAYMENT_FAILED }

    @FunctionalInterface
    public interface Payment {
        boolean charge(String resource, BigDecimal amount);
    }

    public static Result contribute(GoalLedger ledger, String resource, BigDecimal amount, Payment payment) {
        if (ledger == null || resource == null || amount == null || payment == null
                || amount.signum() <= 0 || amount.compareTo(ledger.remaining(resource)) > 0
                || ledger.remaining(resource).signum() <= 0) return Result.INVALID;
        if (!payment.charge(resource, amount)) return Result.PAYMENT_FAILED;
        return ledger.contribute(resource, amount) ? Result.SUCCESS : Result.INVALID;
    }
}
