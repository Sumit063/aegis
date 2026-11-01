package dev.aegis.domain.check;

import java.time.Duration;
import java.util.Objects;

/**
 * Provider behaviour the checks depend on, declared by the provider's adapter (never assumed):
 *
 * @param freshness the provider's retry window; an older message is a replay
 * @param lateSuccessAfterFailure the provider documents that a payment reported FAILED can still
 *     succeed later (Razorpay's late authorization); without it, SUCCESS after FAILED is invalid
 */
public record Policy(Duration freshness, boolean lateSuccessAfterFailure) {

    public Policy {
        Objects.requireNonNull(freshness, "freshness");
    }
}
