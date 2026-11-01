package dev.aegis.application.port.out;

import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Storage of validations: one row each, holding the whole call (LLD §7). */
public interface ValidationRepository {

    /** Stores a new validation with its request and reply; returns it with its ID. */
    Validation insert(Validation validation, Evidence exchange, Instant requestedAt, Instant answeredAt);

    /**
     * Locks the validation a provider reference points to, for the rest of the transaction. Every
     * check about its payments runs under this lock (DECISIONS D5).
     */
    Optional<Validation> lock(Ref provider, Ref providerRef);

    Optional<Validation> find(long id);

    /** Validations whose merchant or provider reference is {@code ref}. */
    List<Validation> findByRef(String ref);

    /** Persists a status change (e.g. PAID). */
    void update(Validation validation);
}
