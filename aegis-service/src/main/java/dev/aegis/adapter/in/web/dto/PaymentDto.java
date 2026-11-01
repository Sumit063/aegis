package dev.aegis.adapter.in.web.dto;

import dev.aegis.domain.payment.Payment;
import java.time.Instant;
import java.util.List;

/** A payment as operators see it: status and every message's verdict and delivery, never bodies. */
public record PaymentDto(
        long id, Long validationId, String provider, String paymentRef, String status, List<Entry> messages) {

    public record Entry(
            int n,
            String type,
            String messageId,
            Instant at,
            String outcome,
            String verdict,
            int duplicates,
            String forward) {}

    public static PaymentDto of(Payment payment) {
        return new PaymentDto(
                payment.id(),
                payment.validationId(),
                payment.provider().value(),
                payment.paymentRef() == null ? null : payment.paymentRef().value(),
                payment.status() == null ? null : payment.status().name(),
                payment.entries().stream()
                        .map(e -> new Entry(
                                e.n(),
                                e.type().name(),
                                e.messageId().value(),
                                e.at(),
                                e.outcome().name(),
                                e.verdict().toString(),
                                e.duplicates(),
                                e.forward().name()))
                        .toList());
    }
}
