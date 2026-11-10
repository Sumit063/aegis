package dev.aegis.domain.partner;

/** Why a partner request did not reach the application, or why its outcome cannot be given. */
public enum Refusal {
    /** Unknown partner, missing or malformed headers, or a signature that does not verify. */
    UNAUTHENTICATED,
    /** The client certificate is not the one configured for the partner. */
    WRONG_CERTIFICATE,
    /** Signed too long ago or too far in the future: a replay, or a clock to fix. */
    STALE,
    /** The operation or method was not granted to this partner, or the path is not a plain path. */
    NOT_PERMITTED,
    /** Over the partner's or the operation's rate. */
    RATE_LIMIT,
    /** The shared rate store cannot be reached; requests are refused, not let through (fail closed). */
    LIMITS_UNAVAILABLE,
    /** The same request ID is still being executed. */
    IN_PROGRESS,
    /** The request ID was already used for a different request. */
    REQUEST_ID_REUSED,
    /** The application was called and gave no answer: whether it acted is unknown; ask its status. */
    OUTCOME_UNKNOWN,
    /** The application could not be reached; nothing was executed and the request ID is free again. */
    APPLICATION_UNAVAILABLE
}
