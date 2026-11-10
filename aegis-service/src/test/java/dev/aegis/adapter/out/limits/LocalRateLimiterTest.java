package dev.aegis.adapter.out.limits;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.application.port.out.RateLimiterPort.Decision;
import dev.aegis.application.port.out.RateLimiterPort.Rate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LocalRateLimiterTest {

    static final Rate SIXTY_A_MINUTE_BURST_5 = new Rate(60, 5);

    final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    final LocalRateLimiter limiter = new LocalRateLimiter(nanos::get);

    long allowed(String key, int attempts) {
        return IntStream.range(0, attempts)
                .filter(i -> limiter.acquire(key, SIXTY_A_MINUTE_BURST_5).allowed())
                .count();
    }

    @Test
    void aBurstIsAllowedThenTheRequestAfterItIsRefusedWithAnExactRetryAfter() {
        assertThat(allowed("acme", 5)).isEqualTo(5);

        Decision refused = limiter.acquire("acme", SIXTY_A_MINUTE_BURST_5);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void theAllowanceComesBackAtTheSteadyRate() {
        allowed("acme", 5);

        nanos.addAndGet(Duration.ofMillis(999).toNanos());
        assertThat(limiter.acquire("acme", SIXTY_A_MINUTE_BURST_5).allowed()).isFalse();
        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        assertThat(limiter.acquire("acme", SIXTY_A_MINUTE_BURST_5).allowed()).isTrue();
        assertThat(limiter.acquire("acme", SIXTY_A_MINUTE_BURST_5).allowed()).isFalse();
    }

    @Test
    void overAMinuteTheSteadyRateIsHeld() {
        long total = 0;
        for (int second = 0; second < 60; second++) {
            total += allowed("acme", 10);
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        }

        // The initial burst, then one a second.
        assertThat(total).isBetween(60L, 65L);
    }

    @Test
    void keysAreIndependent() {
        allowed("acme", 50);

        assertThat(allowed("other", 5)).isEqualTo(5);
    }

    @Test
    void aClockThatStepsBackDoesNotLockAKeyOut() {
        allowed("acme", 5);
        nanos.addAndGet(-Duration.ofHours(1).toNanos());

        assertThat(limiter.acquire("acme", SIXTY_A_MINUTE_BURST_5).allowed()).isTrue();
    }

    @Test
    void idleKeysRefillOnlyUpToTheBurst() {
        allowed("acme", 1);
        nanos.addAndGet(Duration.ofHours(1).toNanos());

        assertThat(allowed("acme", 10)).isEqualTo(5);
    }
}
