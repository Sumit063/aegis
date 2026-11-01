package dev.aegis.domain.check;

import java.time.Duration;
import java.util.Objects;

/**
 * Provider behaviour the checks depend on, declared by the provider's adapter (never assumed):
 *
 * @param freshness the provider's retry window; an older message is a replay
 * @param lateSuccessAfterFailure the provider documents that a payment reported FAILED can still
 *     succeed later (Razorpay's late authorization); without it, SUCCESS after FAILED is invalid
 * @param maxClockSkew how far in the future a provider timestamp may be (clocks differ); further
 *     ahead, the message is blocked
 */
public record Policy(Duration freshness, boolean lateSuccessAfterFailure, Duration maxClockSkew) {

    /** Allowed difference between a provider's clock and Aegis's unless the adapter says otherwise. */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofMinutes(5);

    public Policy {
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(maxClockSkew, "maxClockSkew");
        if (maxClockSkew.isNegative()) {
            throw new IllegalArgumentException("maxClockSkew must not be negative");
        }
    }

    public Policy(Duration freshness, boolean lateSuccessAfterFailure) {
        this(freshness, lateSuccessAfterFailure, DEFAULT_CLOCK_SKEW);
    }
}
