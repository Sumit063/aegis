package dev.aegis.provider.spi;

import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;

/** What the provider answered to an application-initiated call. */
public sealed interface Reply {

    /**
     * Answer to a validation.
     *
     * @param providerRef null when not accepted
     * @param amount the amount the provider fixed; null when not accepted
     */
    record Validated(boolean accepted, Ref providerRef, Money amount) implements Reply {}

    /** Answer to a payment request or a status check, as a provider-neutral message. */
    record Payment(PaymentMessage message) implements Reply {}
}
