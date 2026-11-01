package dev.aegis.domain.check;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One rule a message must satisfy. Returns the reason to block it, or empty to let it continue. */
@FunctionalInterface
public interface Check {

    Optional<BlockReason> apply(Context context);

    /**
     * Everything a rule may look at. {@code validation} and {@code payment} are null when none exists;
     * {@code siblings} are the other payments of the same validation.
     */
    record Context(
            PaymentMessage message,
            Validation validation,
            Payment payment,
            List<Payment> siblings,
            Instant now,
            Duration freshness,
            Limits limits) {

        public Context {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(freshness, "freshness");
            Objects.requireNonNull(limits, "limits");
            siblings = List.copyOf(siblings);
        }
    }

    /** A provider's status-check limits, e.g. NPCI: first check after 90 s, at most 3 in 2 hours. */
    record Limits(Duration firstAfter, int maxChecks, Duration window) {

        public static final Limits NONE = new Limits(Duration.ZERO, Integer.MAX_VALUE, Duration.ZERO);

        public Limits {
            Objects.requireNonNull(firstAfter, "firstAfter");
            Objects.requireNonNull(window, "window");
            if (maxChecks < 1) {
                throw new IllegalArgumentException("maxChecks must be at least 1");
            }
        }
    }
}
