package dev.aegis.domain.check;

import static dev.aegis.domain.check.Fixtures.RS_499;
import static dev.aegis.domain.check.Fixtures.T0;
import static dev.aegis.domain.check.Fixtures.message;
import static dev.aegis.domain.check.Fixtures.validation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChecksTest {

    private static final Validation CREATED = validation(1, "razorpay", "order_TEST", RS_499);

    private static PaymentMessage webhook(Outcome outcome, Money amount) {
        return message("razorpay", MessageType.WEBHOOK, "evt_TEST", "order_TEST", "pay_TEST", outcome, amount, T0);
    }

    private static Check.Context context(PaymentMessage message, Validation validation, Payment payment) {
        return new Check.Context(message, validation, payment, List.of(), T0, Fixtures.RAZORPAY, Check.Limits.NONE);
    }

    private static Payment paymentWithStatus(Outcome status) {
        return new Payment(1L, 1L, Ref.of("razorpay"), Ref.of("pay_TEST"), status, List.of());
    }

    @ParameterizedTest(name = "sent {0} h ago → replay: {1}")
    @CsvSource({"1, false", "25, false", "26, true"})
    void freshMessagesPassAndOldOnesAreReplays(long hoursAgo, boolean replay) {
        PaymentMessage message = sentAt(T0.minus(Duration.ofHours(hoursAgo)));

        assertThat(Checks.FRESH.apply(context(message, CREATED, null)))
                .isEqualTo(replay ? Optional.of(BlockReason.REPLAY) : Optional.empty());
    }

    /** Razorpay: 25 h back, 5 min ahead; both bounds inclusive, one second beyond blocks. */
    @ParameterizedTest(name = "sent at now {0} s → {1}")
    @CsvSource({
        "-90000, OK",
        "-90001, REPLAY",
        "0,      OK",
        "300,    OK",
        "301,    FUTURE_TIMESTAMP",
        "86400,  FUTURE_TIMESTAMP"
    })
    void freshnessWindowBoundaries(long offsetSeconds, String expected) {
        PaymentMessage message = sentAt(T0.plusSeconds(offsetSeconds));

        assertThat(Checks.FRESH.apply(context(message, CREATED, null)))
                .isEqualTo(expected.equals("OK") ? Optional.empty() : Optional.of(BlockReason.valueOf(expected)));
    }

    @Test
    void theAllowedClockSkewComesFromTheProvider() {
        Policy strictClock = new Policy(Duration.ofHours(25), true, Duration.ZERO);
        PaymentMessage oneSecondAhead = sentAt(T0.plusSeconds(1));

        assertThat(Checks.FRESH.apply(new Check.Context(
                        oneSecondAhead, CREATED, null, List.of(), T0, strictClock, Check.Limits.NONE)))
                .contains(BlockReason.FUTURE_TIMESTAMP);
        assertThatThrownBy(() -> new Policy(Duration.ofHours(1), false, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PaymentMessage sentAt(java.time.Instant at) {
        return message(
                "razorpay", MessageType.WEBHOOK, "evt_TEST", "order_TEST", "pay_TEST", Outcome.SUCCESS, null, at);
    }

    @Test
    void messagesWithoutASendTimeAreNotJudgedOnFreshness() {
        PaymentMessage noTime = new PaymentMessage(
                Ref.of("razorpay"),
                MessageType.CALLBACK,
                Ref.of("cb:pay_TEST"),
                Ref.of("order_TEST"),
                Ref.of("pay_TEST"),
                Outcome.SUCCESS,
                null,
                null,
                T0);

        assertThat(Checks.FRESH.apply(context(noTime, CREATED, null))).isEmpty();
    }

    @Test
    void aMessageNeedsAnAcceptedValidation() {
        Validation rejected =
                new Validation(2L, Ref.of("razorpay"), Ref.of("SHOP-2"), null, null, Validation.Status.FAILED);

        assertThat(Checks.VALIDATED.apply(context(webhook(Outcome.SUCCESS, null), null, null)))
                .contains(BlockReason.NO_VALIDATION);
        assertThat(Checks.VALIDATED.apply(context(webhook(Outcome.SUCCESS, null), rejected, null)))
                .contains(BlockReason.NO_VALIDATION);
        assertThat(Checks.VALIDATED.apply(context(webhook(Outcome.SUCCESS, null), CREATED, null)))
                .isEmpty();
    }

    @Test
    void amountsMustMatchExactlyWhenPresent() {
        assertThat(Checks.AMOUNT_MATCHES.apply(context(webhook(Outcome.SUCCESS, RS_499), CREATED, null)))
                .isEmpty();
        assertThat(Checks.AMOUNT_MATCHES.apply(context(webhook(Outcome.SUCCESS, null), CREATED, null)))
                .isEmpty();
        assertThat(Checks.AMOUNT_MATCHES.apply(
                        context(webhook(Outcome.SUCCESS, Money.of(1_000, "INR")), CREATED, null)))
                .contains(BlockReason.AMOUNT_MISMATCH);
        assertThat(Checks.AMOUNT_MATCHES.apply(
                        context(webhook(Outcome.SUCCESS, Money.of(49_900, "USD")), CREATED, null)))
                .contains(BlockReason.AMOUNT_MISMATCH);
    }

    /** Once a validation is paid, only the payment that paid it moves on. */
    @ParameterizedTest(name = "{0} on another attempt of a paid validation → {1}")
    @CsvSource({"SUCCESS, DUPLICATE_PAYMENT", "FAILED, DOWNGRADE", "PENDING, DOWNGRADE"})
    void anotherAttemptCannotChangeAPaidValidation(Outcome outcome, BlockReason reason) {
        assertThat(Checks.NOT_PAID_ELSEWHERE.apply(context(webhook(outcome, null), CREATED.markPaid(), null)))
                .contains(reason);
        assertThat(Checks.NOT_PAID_ELSEWHERE.apply(context(webhook(outcome, null), CREATED, null)))
                .isEmpty();
    }

    @Test
    void theSameFactIsForwardedOnceButAnAnswerMayConfirmIt() {
        Payment paid = paymentWithStatus(Outcome.SUCCESS);
        PaymentMessage reply = message(
                "razorpay", MessageType.STATUS_CHECK, "aegis:1", "order_TEST", "pay_TEST", Outcome.SUCCESS, null, T0);

        assertThat(Checks.TRANSITION.apply(context(webhook(Outcome.SUCCESS, null), CREATED, paid)))
                .contains(BlockReason.DUPLICATE);
        assertThat(Checks.TRANSITION.apply(context(reply, CREATED, paid)))
                .as("a status-check reply confirming the state is an answer, not a duplicate")
                .isEmpty();
        assertThat(Checks.TRANSITION.apply(context(webhook(Outcome.SUCCESS, null), CREATED, null)))
                .isEmpty();
    }

    @Test
    void refundsAreNotComparedWithTheOrderAmountButWithWhatWasPaid() {
        PaymentMessage refund = message(
                "razorpay",
                MessageType.WEBHOOK,
                "evt_r",
                "order_TEST",
                "pay_TEST",
                Outcome.REFUNDED,
                Money.of(100, "INR"),
                T0);
        PaymentMessage foreign = message(
                "razorpay",
                MessageType.WEBHOOK,
                "evt_r",
                "order_TEST",
                "pay_TEST",
                Outcome.REFUNDED,
                Money.of(100, "USD"),
                T0);

        assertThat(Checks.AMOUNT_MATCHES.apply(context(refund, CREATED, null))).isEmpty();
        assertThat(Checks.REFUND_WITHIN_PAYMENT.apply(context(refund, CREATED, null)))
                .isEmpty();
        assertThat(Checks.REFUND_WITHIN_PAYMENT.apply(context(foreign, CREATED, null)))
                .contains(BlockReason.AMOUNT_MISMATCH);
    }

    /** A status reply's cumulative refunded amount is checked against what was paid, not added to refunds. */
    @Test
    void aRefundObservationIsTheCumulativeTotal() {
        PaymentMessage full = message(
                "razorpay",
                MessageType.STATUS_CHECK,
                "status:1",
                "order_TEST",
                "pay_TEST",
                Outcome.REFUNDED,
                RS_499,
                T0);
        PaymentMessage beyond = message(
                "razorpay",
                MessageType.STATUS_CHECK,
                "status:2",
                "order_TEST",
                "pay_TEST",
                Outcome.REFUNDED,
                Money.of(49_901, "INR"),
                T0);

        assertThat(Checks.REFUND_WITHIN_PAYMENT.apply(context(full, CREATED, paymentWithStatus(Outcome.SUCCESS))))
                .isEmpty();
        assertThat(Checks.REFUND_WITHIN_PAYMENT.apply(context(beyond, CREATED, paymentWithStatus(Outcome.SUCCESS))))
                .contains(BlockReason.AMOUNT_MISMATCH);
    }

    @Test
    void runStopsAtTheFirstBlock() {
        Check never = c -> {
            throw new AssertionError("must not run after a block");
        };

        assertThat(Checks.run(List.of(Checks.VALIDATED, never), context(webhook(Outcome.SUCCESS, null), null, null)))
                .isEqualTo(Verdict.blocked(BlockReason.NO_VALIDATION));
        assertThat(Checks.run(List.of(), context(webhook(Outcome.SUCCESS, null), null, null)))
                .isEqualTo(Verdict.PASSED);
    }

    @Test
    void limitsNeedAtLeastOneCheck() {
        assertThatThrownBy(() -> new Check.Limits(Duration.ZERO, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
