package dev.aegis.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * A validated identifier: a provider, a provider's order or payment reference, a merchant reference
 * or a message ID. One type for all of them keeps the model small; the field name says which it is.
 */
public record Ref(String value) {

    private static final int MAX_LENGTH = 128;
    private static final String UNVERIFIED = "unverified:";

    public Ref {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("reference must be 1-" + MAX_LENGTH + " printable characters");
        }
    }

    public static Ref of(String value) {
        return new Ref(value);
    }

    /** An ID for a message that could not be trusted, so it can never claim a genuine message's ID. */
    public static Ref unverified(UUID id) {
        return new Ref(UNVERIFIED + id);
    }

    public boolean isUnverified() {
        return value.startsWith(UNVERIFIED);
    }

    @Override
    public String toString() {
        return value;
    }
}
