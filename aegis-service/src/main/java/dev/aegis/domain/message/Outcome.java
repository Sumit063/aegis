package dev.aegis.domain.message;

/** What a message says happened, in provider-neutral terms. */
public enum Outcome {
    PENDING(1),
    SUCCESS(2),
    FAILED(2),
    REFUNDED(3),
    /** Events Aegis does not interpret (settlements, disputes); checked for authenticity only. */
    OTHER(0);

    private final int rank;

    Outcome(int rank) {
        this.rank = rank;
    }

    /**
     * How far along a payment this outcome is. A message ranked below the payment's current status is
     * stale. SUCCESS and FAILED share a rank because providers authorize late (failed, then success).
     */
    public int rank() {
        return rank;
    }
}
