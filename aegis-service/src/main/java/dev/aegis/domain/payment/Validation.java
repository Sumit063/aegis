package dev.aegis.domain.payment;

import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.util.Objects;

/**
 * One validation call: the authority (gateway, NPCI) fixed {@code amount} for {@code providerRef}.
 * Every payment message about it is checked against this. {@code id} is null until stored;
 * {@code providerRef} and {@code amount} are null only when the provider rejected the call.
 */
public record Validation(Long id, Ref provider, Ref merchantRef, Ref providerRef, Money amount, Status status) {

    public enum Status {
        CREATED,
        FAILED,
        PAID
    }

    public Validation {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(merchantRef, "merchantRef");
        Objects.requireNonNull(status, "status");
        if (status != Status.FAILED && (providerRef == null || amount == null)) {
            throw new IllegalArgumentException("an accepted validation needs a provider reference and an amount");
        }
    }

    /** Payment requests may go out only against a validation that is neither failed nor already paid. */
    public boolean isPayable() {
        return status == Status.CREATED;
    }

    public Validation markPaid() {
        return status == Status.PAID
                ? this
                : new Validation(id, provider, merchantRef, providerRef, amount, Status.PAID);
    }
}
