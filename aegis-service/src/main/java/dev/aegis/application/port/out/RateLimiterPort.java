package dev.aegis.application.port.out;

import java.time.Duration;

/**
 * Rates per key (a partner, a partner's operation). A strategy chosen by {@code aegis.partner.limiter}:
 * where the allowances are kept is the adapter's business.
 */
public interface RateLimiterPort {

    /** {@code perMinute} requests a minute on average, up to {@code burst} at once. */
    record Rate(int perMinute, int burst) {

        public Rate {
            if (perMinute < 1 || burst < 1) {
                throw new IllegalArgumentException("a rate must be positive");
            }
        }
    }

    /** @param retryAfter when a refused request could succeed; zero when allowed */
    record Decision(boolean allowed, Duration retryAfter) {

        public static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }

    /**
     * Takes one request from {@code key}'s allowance if there is one.
     *
     * @throws IllegalStateException when the store holding the allowances cannot be reached
     */
    Decision acquire(String key, Rate rate);
}
