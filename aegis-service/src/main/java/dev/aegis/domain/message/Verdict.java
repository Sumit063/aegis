package dev.aegis.domain.message;

import java.util.Objects;

/** Aegis's decision on a message: passed, or blocked with a reason. {@code reason} is null when passed. */
public record Verdict(BlockReason reason) {

    public static final Verdict PASSED = new Verdict(null);

    public static Verdict blocked(BlockReason reason) {
        return new Verdict(Objects.requireNonNull(reason, "reason"));
    }

    public boolean passed() {
        return reason == null;
    }

    @Override
    public String toString() {
        return passed() ? "PASSED" : "BLOCKED " + reason;
    }
}
