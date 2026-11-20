package dev.bordergate.core;

/** Automatic settlement decision; explicit administrator actions remain separate. */
public final class QuestSettlementPolicy {
    public enum Outcome { PENDING, SUCCESS, FAILURE }
    private QuestSettlementPolicy() {}

    public static Outcome evaluate(long now, long deadline, boolean complete, boolean settleOnCompletion) {
        if (now < deadline && !(settleOnCompletion && complete)) return Outcome.PENDING;
        return complete ? Outcome.SUCCESS : Outcome.FAILURE;
    }
}
