package dev.aegis.application.port.in;

import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Investigation and operations (LLD §2 Admin): look up, list blocks, re-forward. */
public interface AdminUseCase {

    Optional<Payment> payment(long id);

    /** Payments whose payment reference is {@code ref}, or whose validation has it as merchant or provider reference. */
    List<Payment> paymentsByRef(String ref);

    /** Validations whose merchant or provider reference is {@code ref}. */
    List<Validation> validationsByRef(String ref);

    /** Payments with at least one blocked message, most recently changed first. */
    List<Payment> blockedSince(Instant since, int limit);

    /**
     * Sends a passed message to its application again (e.g. after it was FAILED).
     *
     * @throws BadRequestException when the entry does not exist, was blocked, or is not forwarded
     */
    void reforward(long paymentId, int entry);

    /** Opens the {@code sample} most recent sealed values to prove the configured keys still read them. */
    EvidenceReport evidence(int sample);

    /** A partner request by its request ID (LLD §14). */
    Optional<PartnerRequestView> partnerRequest(String partner, String requestId);
}
