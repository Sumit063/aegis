package dev.aegis.adapter.out.limits;

import dev.aegis.application.port.out.RateLimiterPort.Decision;
import dev.aegis.application.port.out.RateLimiterPort.Rate;
import java.time.Duration;

/**
 * The generic cell rate algorithm: one number per key, the theoretical arrival time (TAT) of the next
 * request. A request is allowed while the TAT it would leave is at most {@code burst} intervals
 * ahead of now. Equivalent to a token bucket, with an exact retry-after and nothing to refill.
 */
final class Gcra {

    /** @param tat the key's new TAT when allowed (store it), unchanged otherwise */
    record Step(Decision decision, long tat) {}

    private Gcra() {}

    static long intervalNanos(Rate rate) {
        return 60_000_000_000L / rate.perMinute();
    }

    /**
     * @param stored the key's TAT, or null for a new key
     * @param now nanoseconds on a clock that only moves forward
     */
    static Step step(Long stored, long now, Rate rate) {
        long interval = intervalNanos(rate);
        long horizon = interval * rate.burst();
        // A TAT beyond the horizon cannot be reached by counting; the clock went back. Start afresh.
        long tat = stored == null || stored < now || stored - now > horizon ? now : stored;
        long next = tat + interval;
        if (next - now > horizon) {
            return new Step(new Decision(false, Duration.ofNanos(next - now - horizon)), tat);
        }
        return new Step(Decision.ALLOWED, next);
    }
}
