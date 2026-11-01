package dev.aegis.adapter.out.persistence;

import dev.aegis.adapter.out.persistence.repository.PaymentJpaRepository;
import dev.aegis.adapter.out.persistence.repository.ValidationJpaRepository;
import dev.aegis.application.port.out.RetentionRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Batched deletes: payments first, then validations no payment refers to. */
@Component
class RetentionPersistenceAdapter implements RetentionRepository {

    private final PaymentJpaRepository payments;
    private final ValidationJpaRepository validations;

    RetentionPersistenceAdapter(PaymentJpaRepository payments, ValidationJpaRepository validations) {
        this.payments = payments;
        this.validations = validations;
    }

    @Override
    public int purgeOlderThan(Instant cutoff, int batch) {
        int deleted = payments.deleteOlderThan(cutoff, batch);
        return deleted + validations.deleteOlderThan(cutoff, batch - deleted);
    }
}
