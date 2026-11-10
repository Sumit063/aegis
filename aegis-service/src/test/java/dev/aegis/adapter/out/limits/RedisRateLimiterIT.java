package dev.aegis.adapter.out.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.application.port.out.RateLimiterPort.Decision;
import dev.aegis.application.port.out.RateLimiterPort.Rate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/** D36 against a real Redis: one allowance for every instance (T29), fail closed and recover (T30). */
class RedisRateLimiterIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6.8-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    static final List<RedisRateLimiter> OPEN = new ArrayList<>();

    static RedisRateLimiter instance() {
        RedisRateLimiter limiter = new RedisRateLimiter(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379), Duration.ofMillis(250), "LOCAL");
        OPEN.add(limiter);
        return limiter;
    }

    @AfterAll
    static void close() {
        OPEN.forEach(RedisRateLimiter::close);
    }

    static String key() {
        return "partner:" + UUID.randomUUID();
    }

    @Test
    void aBurstIsAllowedThenRefusedWithTheTimeToWait() {
        RedisRateLimiter limiter = instance();
        String key = key();
        Rate rate = new Rate(60, 5);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.acquire(key, rate).allowed()).isTrue();
        }
        Decision refused = limiter.acquire(key, rate);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    @Test
    void twoInstancesShareOneAllowance() throws Exception {
        List<RedisRateLimiter> instances = List.of(instance(), instance());
        String key = key();
        Rate rate = new Rate(60, 20);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            RedisRateLimiter limiter = instances.get(i % 2);
            attempts.add(() -> limiter.acquire(key, rate).allowed());
        }
        int allowed = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Boolean> attempt : pool.invokeAll(attempts)) {
                allowed += attempt.get() ? 1 : 0;
            }
        }

        // The burst once for both, not once each; at most one more if a second passes meanwhile.
        assertThat(allowed).isBetween(20, 21);
    }

    @Test
    void keysAreIndependentAndExpireWhenIdle() {
        RedisRateLimiter limiter = instance();
        String busy = key();
        Rate rate = new Rate(60, 1);
        limiter.acquire(busy, rate);

        assertThat(limiter.acquire(key(), rate).allowed()).isTrue();
        assertThat(ttl(busy)).as("kept only while it limits anything").isBetween(1L, 2_100L);
    }

    @Test
    void anUnreachableRedisIsRefusedFastAndRecoveredFrom() {
        RedisRateLimiter limiter = instance();
        Rate rate = new Rate(6000, 100);
        assertThat(limiter.acquire(key(), rate).allowed()).isTrue();
        var docker = DockerClientFactory.instance().client();

        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> limiter.acquire(key(), rate)).isInstanceOf(IllegalStateException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        assertThat(limiter.acquire(key(), rate).allowed()).isTrue();
    }

    private static long ttl(String key) {
        try {
            String out = REDIS.execInContainer("redis-cli", "PTTL", "aegis:local:rate:" + key)
                    .getStdout()
                    .trim();
            return Long.parseLong(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
