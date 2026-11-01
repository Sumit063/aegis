package dev.aegis.domain.message;

/** Why a message was blocked (LLD §5). */
public enum BlockReason {
    BAD_SIGNATURE,
    UNPARSEABLE,
    REPLAY,
    NO_VALIDATION,
    AMOUNT_MISMATCH,
    DOWNGRADE,
    DUPLICATE,
    DUPLICATE_PAYMENT,
    RATE_LIMIT
}
