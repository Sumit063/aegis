package dev.aegis.application.port.out;

import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Storage of payments: one row each, with every message about it (LLD §7). */
public interface PaymentRepository {

    /**
     * Locks the row for this payment reference for the rest of the transaction, creating it first if
     * it does not exist ({@code INSERT … ON CONFLICT DO NOTHING}, then {@code SELECT … FOR UPDATE}).
     */
    Payment lockOrCreate(Ref provider, Ref paymentRef, Long validationId);

    Optional<Payment> find(long id);

    /** Locks the row for this payment reference if it exists. */
    Optional<Payment> lock(Ref provider, Ref paymentRef);

    /** Payments with this payment reference, or belonging to a validation with this merchant/provider reference. */
    List<Payment> findByRef(String ref);

    /** Payments with a blocked message, changed since {@code since}, newest first. */
    List<Payment> blockedSince(Instant since, int limit);

    /** The payments of a validation, to check for one still in flight. */
    List<Payment> ofValidation(long validationId);

    /**
     * Writes the payment's status and entries. {@code evidence} holds, by message ID, what to keep of
     * the entries added in this transaction; earlier entries keep what they have.
     */
    Payment save(Payment payment, Map<Ref, Evidence> evidence);

    /**
     * Records the provider's synchronous reply on an outbound request already stored as entry
     * {@code n} (the request was written first, so it counted as in flight while being sent).
     */
    void attachReply(long paymentId, int n, String sealedReply, int httpStatus);

    /** Stores a message that failed before it could be trusted, in a row of its own. */
    Payment insertUntrusted(Payment payment, Evidence evidence);
}
