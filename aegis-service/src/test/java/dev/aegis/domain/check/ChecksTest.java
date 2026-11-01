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
        return new Check.Context(message, validation, payment, List.of(), T0, Duration.ofHours(25), Check.Limits.NONE);
    }

    private static Payment paymentWithStatus(Outcome status) {
        return new Payment(1L, 1L, Ref.of("razorpay"), Ref.of("pay_TEST"), status, List.of());
    }

    @ParameterizedTest(name = "sent {0} h ago → replay: {1}")
    @CsvSource({"1, false", "25, false", "26, true"})
    void freshMessagesPassAndOldOnesAreReplays(long hoursAgo, boolean replay) {
        PaymentMessage message = message(
                "razorpay",
                MessageType.WEBHOOK,
                "evt_TEST",
                "order_TEST",
                "pay_TEST",
                Outcome.SUCCESS,
                null,
                T0.minus(Duration.ofHours(hoursAgo)));

        assertThat(Checks.FRESH.apply(context(message, CREATED, null)))
                .isEqualTo(replay ? Optional.of(BlockReason.REPLAY) : Optional.empty());
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

    @ParameterizedTest(name = "{0} on a payment that is {1} (validation paid: {2}) → downgrade: {3}")
    @CsvSource({
        "FAILED,   ,        true,  true",
        "FAILED,   ,        false, false",
        "PENDING,  SUCCESS, true,  true",
        "SUCCESS,  FAILED,  false, false",
        "REFUNDED, SUCCESS, true,  false",
        "SUCCESS,  PENDING, false, false"
    })
    void nothingMovesAPaymentBackwards(Outcome outcome, Outcome current, boolean paid, boolean downgrade) {
        Validation validation = paid ? CREATED.markPaid() : CREATED;
        Payment payment = current == null ? null : paymentWithStatus(current);

        assertThat(Checks.NO_DOWNGRADE.apply(context(webhook(outcome, null), validation, payment)))
                .isEqualTo(downgrade ? Optional.of(BlockReason.DOWNGRADE) : Optional.empty());
    }

    @Test
    void theSameFactIsForwardedOnce() {
        Payment paid = paymentWithStatus(Outcome.SUCCESS);
        PaymentMessage reply = message(
                "razorpay", MessageType.STATUS_CHECK, "aegis:1", "order_TEST", "pay_TEST", Outcome.SUCCESS, null, T0);

        assertThat(Checks.NEW_FACT.apply(context(webhook(Outcome.SUCCESS, null), CREATED, paid)))
                .contains(BlockReason.DUPLICATE);
        assertThat(Checks.NEW_FACT.apply(context(webhook(Outcome.REFUNDED, null), CREATED, paid)))
                .isEmpty();
        assertThat(Checks.NEW_FACT.apply(context(reply, CREATED, paid)))
                .as("replies return to the caller")
                .isEmpty();
        assertThat(Checks.NEW_FACT.apply(context(webhook(Outcome.SUCCESS, null), CREATED, null)))
                .isEmpty();
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
