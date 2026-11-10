package dev.aegis.application.service;

import java.time.Duration;

/**
 * How partner requests are judged ({@code aegis.partner.*}, LLD §11).
 *
 * @param freshness how far a request's signing time may be from Aegis's clock, either way
 * @param answerTimeout how long an execution may take; a request ID in progress for longer has an
 *     unknown outcome (the instance running it stopped). Above the 10 s the application has to answer
 * @param retention how long request IDs (and their answers) are kept; far above {@code freshness},
 *     so an ID is never forgotten while a request signed with it could still be accepted
 */
public record PartnerSettings(Duration freshness, Duration answerTimeout, Duration retention) {

    public static final PartnerSettings DEFAULTS =
            new PartnerSettings(Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofDays(30));

    public PartnerSettings {
        if (!freshness.isPositive() || answerTimeout.compareTo(Duration.ofSeconds(15)) < 0) {
            throw new IllegalArgumentException(
                    "aegis.partner.freshness must be positive and answer-timeout at least 15s");
        }
        if (retention.compareTo(freshness.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("aegis.partner.retention must be at least twice the freshness");
        }
    }
}
