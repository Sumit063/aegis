package dev.aegis.application.service;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key token buckets: {@code perMinute} tokens refill evenly over a minute, up to {@code burst}.
 * In-memory and per instance, so limits are approximate across instances by design (they bound load,
 * they do not count money). Keys are configured connection names, so the map stays small.
 */
public final class TokenBucket {

    private record State(double tokens, long atNanos) {}

    private final double perNano;
    private final double burst;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public TokenBucket(int perMinute, int burst, Clock clock) {
        if (perMinute < 1 || burst < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.perNano = perMinute / 60e9;
        this.burst = burst;
        this.clock = clock;
    }

    /** Takes a token for {@code key} if one is available. */
    public boolean tryAcquire(String key) {
        long now = nanos();
        boolean[] granted = new boolean[1];
        states.compute(key, (k, state) -> {
            double tokens = state == null ? burst : Math.min(burst, state.tokens() + (now - state.atNanos()) * perNano);
            granted[0] = tokens >= 1;
            return new State(granted[0] ? tokens - 1 : tokens, now);
        });
        return granted[0];
    }

    private long nanos() {
        var instant = clock.instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
