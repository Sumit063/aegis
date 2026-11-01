package dev.aegis.domain.check;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Duration;
import java.time.Instant;

/** Obviously fake test data shared by the check tests. */
final class Fixtures {

    static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    /** Razorpay: 24 h of retries, late authorization documented. */
    static final Policy RAZORPAY = new Policy(Duration.ofHours(25), true);
    /** A provider without late success (NPCI-like). */
    static final Policy STRICT = new Policy(Duration.ofHours(1), false);

    static final Check.Limits NPCI = new Check.Limits(Duration.ofSeconds(90), 3, Duration.ofHours(2));
    static final Money RS_499 = Money.of(49_900, "INR");
    static final Money RS_840 = Money.of(84_000, "INR");

    private Fixtures() {}

    static Validation validation(long id, String provider, String providerRef, Money amount) {
        return new Validation(
                id, Ref.of(provider), Ref.of("SHOP-" + id), Ref.of(providerRef), amount, Validation.Status.CREATED);
    }

    static PaymentMessage message(
            String provider,
            MessageType type,
            String id,
            String providerRef,
            String paymentRef,
            Outcome outcome,
            Money amount,
            Instant at) {
        return new PaymentMessage(
                Ref.of(provider),
                type,
                Ref.of(id),
                providerRef == null ? null : Ref.of(providerRef),
                paymentRef == null ? null : Ref.of(paymentRef),
                outcome,
                amount,
                at,
                at);
    }
}
