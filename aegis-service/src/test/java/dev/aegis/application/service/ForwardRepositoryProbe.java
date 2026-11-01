package dev.aegis.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.application.port.out.ForwardRepository.Attempt;
import dev.aegis.application.port.out.ForwardRepository.Pending;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Checks of the retry schedule, shared by unit and integration tests. */
final class ForwardRepositoryProbe {

    private ForwardRepositoryProbe() {}

    static Pending pending(int attempts, Instant firstAt) {
        return new Pending(1, MessageType.WEBHOOK, Outcome.SUCCESS, Map.of(), null, attempts, firstAt, null);
    }

    static void assertBackoff() {
        Instant now = Instant.parse("2026-11-01T10:00:00Z");

        Attempt first = ForwardService.retry(pending(0, null), now, "HTTP 503");
        assertThat(Duration.between(now, first.nextAt())).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));

        Attempt tenth = ForwardService.retry(pending(9, now.minus(Duration.ofHours(1))), now, "HTTP 503");
        assertThat(Duration.between(now, tenth.nextAt())).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(10));

        Attempt late = ForwardService.retry(pending(40, now.minus(Duration.ofHours(73))), now, "HTTP 503");
        assertThat(late.nextAt()).as("after 72 h the message is FAILED").isNull();
        assertThat(late.delivered()).isFalse();
    }
}
