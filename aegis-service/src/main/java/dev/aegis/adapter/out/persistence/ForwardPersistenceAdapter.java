package dev.aegis.adapter.out.persistence;

import dev.aegis.adapter.out.persistence.entity.PaymentEntity;
import dev.aegis.adapter.out.persistence.repository.PaymentJpaRepository;
import dev.aegis.application.port.out.ForwardRepository;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.shared.Ref;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The forwarding queue on {@code payments}: a payment is due while {@code forward_due_at <= now}, and
 * that time is always the earliest moment one of its pending messages may be sent.
 */
@Component
class ForwardPersistenceAdapter implements ForwardRepository {

    private static final String PENDING = Payment.Forward.PENDING.name();

    private final PaymentJpaRepository rows;
    private final PaymentRows mapper;

    ForwardPersistenceAdapter(PaymentJpaRepository rows, PaymentRows mapper) {
        this.rows = rows;
        this.mapper = mapper;
    }

    @Override
    public List<Claimed> claimDue(Instant now, int limit, Duration lease) {
        return rows.lockDue(now, limit).stream()
                .map(row -> {
                    row.update(row.getStatus(), row.getMessages(), now.plus(lease), now);
                    return claimed(row);
                })
                .toList();
    }

    @Override
    public void record(long paymentId, int n, Attempt attempt) {
        PaymentEntity row =
                rows.lockById(paymentId).orElseThrow(() -> new IllegalStateException("no payment " + paymentId));
        List<StoredEntry> entries = mapper.read(row.getMessages()).stream()
                .map(e -> e.n() == n ? e.with(e.duplicates(), next(e.forward(), attempt)) : e)
                .toList();
        // Messages go strictly in order, so the payment is due when its first pending message is.
        Instant due = entries.stream()
                .filter(e -> e.forward() != null && PENDING.equals(e.forward().status()))
                .min(Comparator.comparingInt(StoredEntry::n))
                .map(e -> e.forward().nextAt() == null
                        ? attempt.at()
                        : e.forward().nextAt())
                .orElse(null);
        row.update(row.getStatus(), mapper.write(entries), due, attempt.at());
    }

    private static StoredEntry.Forward next(StoredEntry.Forward current, Attempt attempt) {
        int attempts = (current == null ? 0 : current.attempts()) + 1;
        Instant first = current == null || current.firstAt() == null ? attempt.at() : current.firstAt();
        String status = attempt.delivered()
                ? Payment.Forward.DELIVERED.name()
                : attempt.nextAt() == null ? Payment.Forward.FAILED.name() : PENDING;
        return new StoredEntry.Forward(status, attempts, first, attempt.at(), attempt.nextAt(), attempt.error());
    }

    private Claimed claimed(PaymentEntity row) {
        List<Pending> pending = mapper.read(row.getMessages()).stream()
                .filter(e -> e.forward() != null && PENDING.equals(e.forward().status()))
                .sorted(Comparator.comparingInt(StoredEntry::n))
                .map(e -> new Pending(
                        e.n(),
                        MessageType.valueOf(e.type()),
                        Outcome.valueOf(e.outcome()),
                        Objects.requireNonNullElse(e.headers(), Map.of()),
                        e.body(),
                        e.forward().attempts(),
                        e.forward().firstAt(),
                        e.forward().nextAt()))
                .toList();
        return new Claimed(
                row.getId(),
                Ref.of(row.getProvider()),
                row.getValidationId(),
                row.getPaymentRef() == null ? null : Ref.of(row.getPaymentRef()),
                pending);
    }
}
