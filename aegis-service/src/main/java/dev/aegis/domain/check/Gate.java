package dev.aegis.domain.check;

import static dev.aegis.domain.check.Checks.AMOUNT_MATCHES;
import static dev.aegis.domain.check.Checks.FRESH;
import static dev.aegis.domain.check.Checks.NEW_FACT;
import static dev.aegis.domain.check.Checks.NO_DOWNGRADE;
import static dev.aegis.domain.check.Checks.PAYABLE;
import static dev.aegis.domain.check.Checks.VALIDATED;
import static dev.aegis.domain.check.Checks.WITHIN_LIMITS;

import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The single entry point of the domain: decides what happens to a message that arrives
 * ({@link #receive}) and whether an outbound call may go out ({@link #permit}). Pure: no I/O.
 */
public final class Gate {

    private static final List<Check> RECEIVE = List.of(FRESH, VALIDATED, AMOUNT_MATCHES, NO_DOWNGRADE, NEW_FACT);
    private static final List<Check> RECEIVE_UNINTERPRETED = List.of(FRESH);
    private static final List<Check> PAY_REQUEST = List.of(VALIDATED, AMOUNT_MATCHES, PAYABLE);
    private static final List<Check> STATUS_CHECK = List.of(WITHIN_LIMITS);

    /** What happened to a received message, with the records to store. */
    public record Result(Verdict verdict, Payment payment, Validation validation, boolean repeat) {}

    private Gate() {}

    /**
     * A message arrived: a callback, webhook or async response, or the reply to an outbound call.
     *
     * @param validation the validation its provider reference points to, or null
     * @param payment the payment with its payment reference, or null if this is the first message
     */
    public static Result receive(
            PaymentMessage message, Validation validation, Payment payment, Instant now, Duration freshness) {
        Payment current = payment != null
                ? payment
                : Payment.open(validation == null ? null : validation.id(), message.provider(), message.paymentRef());

        Optional<Payment.Entry> seen = current.findEntry(message.id());
        if (seen.isPresent()) {
            return new Result(seen.get().verdict(), current.countRepeat(message.id()), validation, true);
        }

        Check.Context context =
                new Check.Context(message, validation, current, List.of(), now, freshness, Check.Limits.NONE);
        Verdict verdict = Checks.run(message.outcome() == Outcome.OTHER ? RECEIVE_UNINTERPRETED : RECEIVE, context);
        boolean paid = verdict.passed() && message.outcome() == Outcome.SUCCESS && validation != null;
        return new Result(verdict, current.append(message, verdict), paid ? validation.markPaid() : validation, false);
    }

    /**
     * May this application-initiated call go out? Only payment requests and status checks are checked;
     * a blocked call is refused and not stored.
     *
     * @param siblings the other payments of the same validation
     */
    public static Verdict permit(
            PaymentMessage request,
            Validation validation,
            Payment payment,
            List<Payment> siblings,
            Instant now,
            Check.Limits limits) {
        List<Check> checks = switch (request.type()) {
            case PAY_REQUEST -> PAY_REQUEST;
            case STATUS_CHECK -> STATUS_CHECK;
            default -> throw new IllegalArgumentException("not an outbound call: " + request.type());
        };
        return Checks.run(
                checks, new Check.Context(request, validation, payment, siblings, now, Duration.ZERO, limits));
    }
}
