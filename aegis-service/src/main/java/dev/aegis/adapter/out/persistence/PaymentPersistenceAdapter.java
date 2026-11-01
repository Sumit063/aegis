package dev.aegis.adapter.out.persistence;

import dev.aegis.adapter.out.persistence.entity.PaymentEntity;
import dev.aegis.adapter.out.persistence.repository.PaymentJpaRepository;
import dev.aegis.application.port.out.Evidence;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.shared.Ref;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link PaymentRepository} on PostgreSQL. Callers hold the transaction (application services). */
@Component
class PaymentPersistenceAdapter implements PaymentRepository {

    private final PaymentJpaRepository rows;
    private final PaymentRows mapper;
    private final Clock clock;

    PaymentPersistenceAdapter(PaymentJpaRepository rows, PaymentRows mapper, Clock clock) {
        this.rows = rows;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Payment lockOrCreate(Ref provider, Ref paymentRef, Long validationId) {
        if (paymentRef == null) {
            // Nothing to be unique on (e.g. an event without a payment): a row of its own, created on save.
            return Payment.open(validationId, provider, null);
        }
        rows.insertIfAbsent(validationId, provider.value(), paymentRef.value());
        PaymentEntity row = rows.findByProviderAndPaymentRef(provider.value(), paymentRef.value())
                .orElseThrow(() -> new IllegalStateException("payment row vanished after insert"));
        return mapper.toDomain(row);
    }

    @Override
    public Optional<Payment> find(long id) {
        return rows.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<Payment> ofValidation(long validationId) {
        return rows.findByValidationIdOrderById(validationId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Payment save(Payment payment, Map<Ref, Evidence> evidence) {
        Instant now = clock.instant();
        PaymentEntity row = payment.id() == null
                ? new PaymentEntity(
                        payment.validationId(),
                        payment.provider().value(),
                        payment.paymentRef() == null
                                ? null
                                : payment.paymentRef().value(),
                        "[]",
                        now)
                : rows.findById(payment.id())
                        .orElseThrow(() -> new IllegalStateException("no payment " + payment.id()));
        List<StoredEntry> entries = mapper.merge(mapper.read(row.getMessages()), payment, evidence);
        Instant due = PaymentRows.awaitsForwarding(payment)
                ? (row.getForwardDueAt() == null ? now : row.getForwardDueAt())
                : null;
        row.update(payment.status() == null ? null : payment.status().name(), mapper.write(entries), due, now);
        return mapper.toDomain(rows.saveAndFlush(row));
    }

    @Override
    public Payment insertUntrusted(Payment payment, Evidence evidence) {
        if (payment.id() != null || payment.paymentRef() != null) {
            throw new IllegalArgumentException("an untrusted message never joins a payment");
        }
        return save(payment, Map.of(payment.entries().getFirst().messageId(), evidence));
    }
}
