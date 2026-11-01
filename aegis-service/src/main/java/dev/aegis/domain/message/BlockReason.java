package dev.aegis.domain.message;

/** Why a message was blocked (LLD §5). */
public enum BlockReason {
    BAD_SIGNATURE,
    UNPARSEABLE,
    /** Older than the provider's retry window. */
    REPLAY,
    /** Dated further in the future than the provider's allowed clock skew. */
    FUTURE_TIMESTAMP,
    NO_VALIDATION,
    AMOUNT_MISMATCH,
    /** A stale outcome: it would move a payment backwards (e.g. FAILED after a capture). */
    DOWNGRADE,
    /** The same fact again, through another channel or with another event ID. */
    DUPLICATE,
    /** A second payment for a validation already paid, or a payment request while one is in flight. */
    DUPLICATE_PAYMENT,
    RATE_LIMIT,
    /** An outcome that cannot follow the payment's current state (e.g. a refund before any capture). */
    INVALID_TRANSITION,
    /** An authenticated event type Aegis does not interpret; acknowledged, stored, never forwarded. */
    UNSUPPORTED
}
