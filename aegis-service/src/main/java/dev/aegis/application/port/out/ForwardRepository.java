package dev.aegis.application.port.out;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.shared.Ref;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The forwarding queue: payments with passed messages the application has not acknowledged yet. */
public interface ForwardRepository {

    /**
     * Claims up to {@code limit} due payments ({@code FOR UPDATE SKIP LOCKED}) and pushes their due
     * time out by {@code lease}, so other instances skip them while they are being sent. Must run in a
     * transaction that commits before anything is sent.
     */
    List<Claimed> claimDue(Instant now, int limit, Duration lease);

    /** Records the outcome of one delivery attempt and recomputes when the payment is next due. */
    void record(long paymentId, int n, Attempt attempt);

    /** One claimed payment with its messages still to deliver, in order. */
    record Claimed(long paymentId, Ref provider, Long validationId, Ref paymentRef, List<Pending> pending) {}

    /** A passed message waiting for the application. */
    record Pending(
            int n,
            MessageType type,
            Outcome outcome,
            Map<String, String> headers,
            String sealedBody,
            int attempts,
            Instant firstAttemptAt,
            Instant nextAt) {}

    /**
     * @param delivered the application acknowledged it
     * @param nextAt when to try again; null with {@code !delivered} means give up (FAILED)
     */
    record Attempt(boolean delivered, Instant at, Instant nextAt, String error) {}
}
