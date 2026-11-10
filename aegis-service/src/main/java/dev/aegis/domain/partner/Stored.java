package dev.aegis.domain.partner;

import java.time.Instant;
import java.util.Objects;

/** What is recorded for a request ID: which request it was, and how far its execution got. */
public record Stored(String requestHash, State state, Instant startedAt) {

    public enum State {
        /** Claimed; the application has been or is being called. */
        IN_PROGRESS,
        /** The application answered; the answer is stored and is what every repeat gets. */
        ANSWERED,
        /** The application was called and did not answer. */
        UNKNOWN
    }

    public Stored {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
