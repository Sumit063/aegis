package dev.aegis.application.port.out;

import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.shared.Ref;
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

    /** The payments of a validation, to check for one still in flight. */
    List<Payment> ofValidation(long validationId);

    /**
     * Writes the payment's status and entries. {@code evidence} holds, by message ID, what to keep of
     * the entries added in this transaction; earlier entries keep what they have.
     */
    Payment save(Payment payment, Map<Ref, Evidence> evidence);

    /** Stores a message that failed before it could be trusted, in a row of its own. */
    Payment insertUntrusted(Payment payment, Evidence evidence);
}
