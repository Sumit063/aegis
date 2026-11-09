package dev.aegis.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    /** A clock the test moves, in either direction. */
    static final class Wall extends Clock {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final Wall clock = new Wall();
    private final TokenBucket bucket = new TokenBucket(60, 5, clock);

    private long granted(int attempts) {
        return IntStream.range(0, attempts)
                .filter(i -> bucket.tryAcquire("bbps"))
                .count();
    }

    @Test
    void allowsTheBurstThenRefillsAtTheRate() {
        assertThat(granted(10)).isEqualTo(5);

        clock.now = clock.now.plus(Duration.ofSeconds(2));

        assertThat(granted(10)).isEqualTo(2);
    }

    @Test
    void keysHaveTheirOwnBudget() {
        granted(10);

        assertThat(bucket.tryAcquire("upi")).isTrue();
    }

    /** Found by the NPCI tests: a clock stepping back two minutes refused a connection for two minutes. */
    @Test
    void aClockSteppingBackDoesNotDrainTheBudget() {
        assertThat(granted(2)).isEqualTo(2);

        clock.now = clock.now.minus(Duration.ofMinutes(2));

        assertThat(granted(10)).isEqualTo(3);
        clock.now = clock.now.plus(Duration.ofSeconds(1));
        assertThat(bucket.tryAcquire("bbps")).isTrue();
    }
}
