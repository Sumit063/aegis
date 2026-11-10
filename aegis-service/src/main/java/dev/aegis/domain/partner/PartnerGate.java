package dev.aegis.domain.partner;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Decides, without I/O, whether an authenticated partner request may go on (LLD §14) and what a
 * request ID that was already used gets. A request ID is executed at most once.
 */
public final class PartnerGate {

    /** What a request with a known request ID gets instead of a second execution. */
    public enum Repeat {
        /** The stored answer. */
        REPLAY,
        /** Nothing yet: the first is still running. */
        IN_PROGRESS,
        /** The first execution's outcome is unknown, and stays so. */
        OUTCOME_UNKNOWN,
        /** A different request under a used ID. */
        REUSED
    }

    private PartnerGate() {}

    /**
     * @param granted the methods granted to the partner for the request's operation, or empty
     * @param freshness how far the signing time may be from now, either way
     */
    public static Optional<Refusal> admit(
            PartnerRequest request, Set<String> granted, Instant now, Duration freshness) {
        if (Duration.between(request.signedAt(), now).abs().compareTo(freshness) > 0) {
            return Optional.of(Refusal.STALE);
        }
        if (request.operation().isEmpty() || !granted.contains(request.method())) {
            return Optional.of(Refusal.NOT_PERMITTED);
        }
        return Optional.empty();
    }

    /**
     * @param answerTimeout how long an execution may run; one in progress for longer was cut off (a
     *     crash) and its outcome is unknown
     */
    public static Repeat repeat(Stored stored, PartnerRequest request, Instant now, Duration answerTimeout) {
        if (!stored.requestHash().equals(request.requestHash())) {
            return Repeat.REUSED;
        }
        return switch (stored.state()) {
            case ANSWERED -> Repeat.REPLAY;
            case UNKNOWN -> Repeat.OUTCOME_UNKNOWN;
            case IN_PROGRESS ->
                Duration.between(stored.startedAt(), now).compareTo(answerTimeout) > 0
                        ? Repeat.OUTCOME_UNKNOWN
                        : Repeat.IN_PROGRESS;
        };
    }
}
