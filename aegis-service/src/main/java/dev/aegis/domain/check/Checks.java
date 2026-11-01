package dev.aegis.domain.check;

import static dev.aegis.domain.message.BlockReason.AMOUNT_MISMATCH;
import static dev.aegis.domain.message.BlockReason.DOWNGRADE;
import static dev.aegis.domain.message.BlockReason.DUPLICATE;
import static dev.aegis.domain.message.BlockReason.DUPLICATE_PAYMENT;
import static dev.aegis.domain.message.BlockReason.NO_VALIDATION;
import static dev.aegis.domain.message.BlockReason.RATE_LIMIT;
import static dev.aegis.domain.message.BlockReason.REPLAY;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The rules (LLD §3–§5), each one named, small and tested on its own. */
public final class Checks {

    /** Older than the provider's retry window: a replay. Messages without a send time skip this. */
    public static final Check FRESH = c -> block(
            c.message().sentAt() != null
                    && c.message().sentAt().isBefore(c.now().minus(c.freshness())),
            REPLAY);

    /** Refers to a validation the provider accepted. */
    public static final Check VALIDATED =
            c -> block(c.validation() == null || c.validation().status() == Validation.Status.FAILED, NO_VALIDATION);

    /** The amount, when the message carries one, is exactly the validated amount and currency. Runs after VALIDATED. */
    public static final Check AMOUNT_MATCHES = c -> block(
            c.message().amount() != null
                    && !c.message().amount().equals(c.validation().amount()),
            AMOUNT_MISMATCH);

    /** A failure cannot undo a paid validation, and no outcome can move a payment backwards. */
    public static final Check NO_DOWNGRADE = c -> {
        Outcome outcome = c.message().outcome();
        boolean paid = c.validation() != null && c.validation().status() == Validation.Status.PAID;
        Outcome current = c.payment() == null ? null : c.payment().status();
        return block(
                (outcome == Outcome.FAILED && paid) || (current != null && outcome.rank() < current.rank()), DOWNGRADE);
    };

    /** The same fact reported again through another channel is not forwarded twice. */
    public static final Check NEW_FACT = c -> block(
            c.message().type().forwarded()
                    && c.payment() != null
                    && c.message().outcome() == c.payment().status(),
            DUPLICATE);

    /** No second payment request after success or while another one is still pending. Runs after VALIDATED. */
    public static final Check PAYABLE = c -> block(
            !c.validation().isPayable() || c.siblings().stream().anyMatch(Payment::isInFlight), DUPLICATE_PAYMENT);

    /** Status checks stay within the provider's limits, so the application is never penalised. */
    public static final Check WITHIN_LIMITS = c -> {
        Payment payment = c.payment();
        if (payment == null) {
            return Optional.empty();
        }
        Check.Limits limits = c.limits();
        Optional<Instant> requested = payment.entries(MessageType.PAY_REQUEST).stream()
                .map(Payment.Entry::at)
                .findFirst();
        boolean tooEarly =
                requested.isPresent() && c.now().isBefore(requested.get().plus(limits.firstAfter()));
        long recent = payment.entries(MessageType.STATUS_CHECK).stream()
                .filter(e -> e.at().isAfter(c.now().minus(limits.window())))
                .count();
        return block(tooEarly || recent >= limits.maxChecks(), RATE_LIMIT);
    };

    private Checks() {}

    /** Runs the rules in order; the first one that blocks decides. */
    public static Verdict run(List<Check> checks, Check.Context context) {
        return checks.stream()
                .map(check -> check.apply(context))
                .flatMap(Optional::stream)
                .findFirst()
                .map(Verdict::blocked)
                .orElse(Verdict.PASSED);
    }

    private static Optional<BlockReason> block(boolean condition, BlockReason reason) {
        return condition ? Optional.of(reason) : Optional.empty();
    }
}
