package dev.aegis.domain.check;

import static dev.aegis.domain.check.Fixtures.NPCI;
import static dev.aegis.domain.check.Fixtures.RAZORPAY;
import static dev.aegis.domain.check.Fixtures.RS_499;
import static dev.aegis.domain.check.Fixtures.RS_840;
import static dev.aegis.domain.check.Fixtures.STRICT;
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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GateTest {

    private static PaymentMessage razorpay(
            MessageType type, String id, String paymentRef, Outcome outcome, Money amount) {
        return message("razorpay", type, id, "order_X", paymentRef, outcome, amount, T0);
    }

    private static Gate.Result receive(PaymentMessage message, Validation validation, Payment payment) {
        return Gate.receive(message, validation, payment, T0, RAZORPAY);
    }

    @Test
    @DisplayName("LLD §9: Razorpay purchase, row by row")
    void razorpayPurchase() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);

        Gate.Result callback =
                receive(razorpay(MessageType.CALLBACK, "cb:pay_Y", "pay_Y", Outcome.SUCCESS, null), order, null);
        assertThat(callback.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(callback.validation().status()).isEqualTo(Validation.Status.PAID);
        assertThat(callback.payment().entries().getFirst().forward()).isEqualTo(Payment.Forward.PENDING);

        Gate.Result webhook = receive(
                razorpay(MessageType.WEBHOOK, "evt_123", "pay_Y", Outcome.SUCCESS, RS_499),
                callback.validation(),
                callback.payment());
        assertThat(webhook.verdict()).as("T5 same fact").isEqualTo(Verdict.blocked(BlockReason.DUPLICATE));

        Gate.Result retried = webhook;
        for (int i = 0; i < 3; i++) {
            retried = receive(
                    razorpay(MessageType.WEBHOOK, "evt_123", "pay_Y", Outcome.SUCCESS, RS_499),
                    retried.validation(),
                    retried.payment());
        }
        assertThat(retried.repeat()).as("T4 repeats are counted").isTrue();
        assertThat(retried.payment().entries()).hasSize(2);
        assertThat(retried.payment().entries().get(1).duplicates()).isEqualTo(3);

        Gate.Result lateFailure = receive(
                razorpay(MessageType.WEBHOOK, "evt_124", "pay_A", Outcome.FAILED, null), retried.validation(), null);
        assertThat(lateFailure.verdict()).as("T9 downgrade").isEqualTo(Verdict.blocked(BlockReason.DOWNGRADE));
    }

    @Test
    @DisplayName("LLD §10: BBPS bill payment at the COU, row by row")
    void bbpsBillPayment() {
        Validation bill = validation(2, "bbps", "R1", RS_840);
        PaymentMessage pay =
                message("bbps", MessageType.PAY_REQUEST, "aegis:req-1", "R1", "T1", Outcome.PENDING, RS_840, T0);

        assertThat(Gate.permit(pay, bill, null, List.of(), T0, NPCI)).isEqualTo(Verdict.PASSED);
        Gate.Result sent = receive(pay, bill, null);
        assertThat(sent.payment().isInFlight()).isTrue();

        PaymentMessage retry =
                message("bbps", MessageType.PAY_REQUEST, "aegis:req-2", "R1", "T2", Outcome.PENDING, RS_840, T0);
        assertThat(Gate.permit(retry, bill, null, List.of(sent.payment()), T0, NPCI))
                .as("T10 double payment")
                .isEqualTo(Verdict.blocked(BlockReason.DUPLICATE_PAYMENT));

        Instant at30s = T0.plusSeconds(30);
        Gate.Result deemed = Gate.receive(
                message("bbps", MessageType.ASYNC_RESPONSE, "npci:1", "R1", "T1", Outcome.PENDING, null, at30s),
                bill,
                sent.payment(),
                at30s,
                STRICT);
        assertThat(deemed.verdict()).as("NPCI's deemed answer is news").isEqualTo(Verdict.PASSED);
        assertThat(deemed.payment().isInFlight()).isTrue();

        PaymentMessage earlyCheck =
                message("bbps", MessageType.STATUS_CHECK, "aegis:chk-1", "R1", "T1", Outcome.PENDING, null, at30s);
        assertThat(Gate.permit(earlyCheck, bill, deemed.payment(), List.of(), at30s, NPCI))
                .as("T11 before 90 s")
                .isEqualTo(Verdict.blocked(BlockReason.RATE_LIMIT));

        Instant at2m = T0.plusSeconds(120);
        PaymentMessage check =
                message("bbps", MessageType.STATUS_CHECK, "aegis:chk-2", "R1", "T1", Outcome.SUCCESS, null, at2m);
        assertThat(Gate.permit(check, bill, deemed.payment(), List.of(), at2m, NPCI))
                .isEqualTo(Verdict.PASSED);
        Gate.Result checked = Gate.receive(check, bill, deemed.payment(), at2m, STRICT);
        assertThat(checked.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(checked.validation().status()).isEqualTo(Validation.Status.PAID);

        Gate.Result late = Gate.receive(
                message("bbps", MessageType.ASYNC_RESPONSE, "npci:2", "R1", "T1", Outcome.SUCCESS, null, at2m),
                checked.validation(),
                checked.payment(),
                at2m,
                STRICT);
        assertThat(late.verdict()).isEqualTo(Verdict.blocked(BlockReason.DUPLICATE));
        assertThat(Gate.permit(retry, checked.validation(), null, List.of(late.payment()), at2m, NPCI))
                .isEqualTo(Verdict.blocked(BlockReason.DUPLICATE_PAYMENT));
    }

    @Test
    void statusChecksAreCappedWithinTheWindow() {
        Validation bill = validation(2, "bbps", "R1", RS_840);
        Payment payment = Payment.open(2L, bill.provider(), null);
        Instant at = T0;
        for (int i = 1; i <= 3; i++) {
            at = at.plusSeconds(600);
            payment = Gate.receive(
                            message(
                                    "bbps",
                                    MessageType.STATUS_CHECK,
                                    "aegis:chk-" + i,
                                    "R1",
                                    "T1",
                                    Outcome.PENDING,
                                    null,
                                    at),
                            bill,
                            payment,
                            at,
                            STRICT)
                    .payment();
        }
        PaymentMessage fourth =
                message("bbps", MessageType.STATUS_CHECK, "aegis:chk-4", "R1", "T1", Outcome.PENDING, null, at);

        assertThat(Gate.permit(fourth, bill, payment, List.of(), at.plusSeconds(60), NPCI))
                .isEqualTo(Verdict.blocked(BlockReason.RATE_LIMIT));
        assertThat(Gate.permit(fourth, bill, payment, List.of(), at.plus(Duration.ofHours(3)), NPCI))
                .isEqualTo(Verdict.PASSED);
    }

    @Test
    void lateAuthorizationAfterAFailurePasses() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);
        Gate.Result failed =
                receive(razorpay(MessageType.WEBHOOK, "evt_1", "pay_Y", Outcome.FAILED, null), order, null);

        Gate.Result captured = receive(
                razorpay(MessageType.WEBHOOK, "evt_2", "pay_Y", Outcome.SUCCESS, RS_499),
                failed.validation(),
                failed.payment());

        assertThat(captured.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(captured.validation().status()).isEqualTo(Validation.Status.PAID);
    }

    @Test
    void stalePendingAfterSuccessIsADowngrade() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);
        Gate.Result captured =
                receive(razorpay(MessageType.WEBHOOK, "evt_1", "pay_Y", Outcome.SUCCESS, RS_499), order, null);

        Gate.Result authorized = receive(
                razorpay(MessageType.WEBHOOK, "evt_0", "pay_Y", Outcome.PENDING, RS_499),
                captured.validation(),
                captured.payment());

        assertThat(authorized.verdict()).isEqualTo(Verdict.blocked(BlockReason.DOWNGRADE));
        assertThat(authorized.payment().status()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void replaysUnknownValidationsAndSwappedAmountsAreBlocked() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);
        PaymentMessage old = message(
                "razorpay",
                MessageType.WEBHOOK,
                "evt_old",
                "order_X",
                "pay_Y",
                Outcome.SUCCESS,
                RS_499,
                T0.minus(Duration.ofDays(30)));

        assertThat(receive(old, order, null).verdict()).as("T6").isEqualTo(Verdict.blocked(BlockReason.REPLAY));
        assertThat(receive(razorpay(MessageType.WEBHOOK, "evt_1", "pay_Y", Outcome.SUCCESS, RS_499), null, null)
                        .verdict())
                .as("T7")
                .isEqualTo(Verdict.blocked(BlockReason.NO_VALIDATION));
        Gate.Result swapped = receive(
                razorpay(MessageType.WEBHOOK, "evt_2", "pay_Y", Outcome.SUCCESS, Money.of(1_000, "INR")), order, null);
        assertThat(swapped.verdict()).as("T8").isEqualTo(Verdict.blocked(BlockReason.AMOUNT_MISMATCH));
        assertThat(swapped.validation().status()).isEqualTo(Validation.Status.CREATED);
    }

    /** Authenticated events Aegis does not interpret are recorded, never forwarded as payment news. */
    @Test
    void unsupportedEventsAreRecordedNotForwarded() {
        PaymentMessage settlement =
                message("razorpay", MessageType.WEBHOOK, "evt_set", null, null, Outcome.OTHER, null, T0);

        Gate.Result result = receive(settlement, null, null);

        assertThat(result.verdict()).isEqualTo(Verdict.blocked(BlockReason.UNSUPPORTED));
        assertThat(result.payment().status()).isNull();
        assertThat(result.payment().entries().getFirst().forward()).isEqualTo(Payment.Forward.NONE);
    }

    /** Partial refunds are separate facts; together they never exceed what was paid. */
    @Test
    void partialRefundsAddUpToAtMostThePayment() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);
        Gate.Result captured =
                receive(razorpay(MessageType.WEBHOOK, "evt_1", "pay_Y", Outcome.SUCCESS, RS_499), order, null);

        Gate.Result first = receive(refund("evt_r1", "rfnd_1", 20_000), captured.validation(), captured.payment());
        Gate.Result again = receive(refund("evt_r1b", "rfnd_1", 20_000), first.validation(), first.payment());
        Gate.Result second = receive(refund("evt_r2", "rfnd_2", 29_900), again.validation(), again.payment());
        Gate.Result tooMuch = receive(refund("evt_r3", "rfnd_3", 100), second.validation(), second.payment());

        assertThat(first.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(again.verdict()).as("same refund, other event ID").isEqualTo(Verdict.blocked(BlockReason.DUPLICATE));
        assertThat(second.verdict()).as("a second partial refund").isEqualTo(Verdict.PASSED);
        assertThat(tooMuch.verdict()).isEqualTo(Verdict.blocked(BlockReason.AMOUNT_MISMATCH));
        assertThat(second.payment().refunded(RS_499.currency())).isEqualTo(RS_499);
        assertThat(second.payment().wasCaptured())
                .as("a refund never erases the capture")
                .isTrue();
        assertThat(second.validation().status()).isEqualTo(Validation.Status.PAID);
    }

    @Test
    void aRefundWithoutACaptureIsInvalid() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);

        assertThat(receive(refund("evt_r", "rfnd_1", 100), order, null).verdict())
                .isEqualTo(Verdict.blocked(BlockReason.INVALID_TRANSITION));
    }

    /** The customer paid twice: the second success is not news to fulfil; it is blocked and alerted. */
    @Test
    void aSecondPaymentForAPaidValidationIsNotForwardedAsNews() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);
        Gate.Result first =
                receive(razorpay(MessageType.WEBHOOK, "evt_1", "pay_A", Outcome.SUCCESS, RS_499), order, null);

        Gate.Result second = receive(
                razorpay(MessageType.WEBHOOK, "evt_2", "pay_B", Outcome.SUCCESS, RS_499), first.validation(), null);

        assertThat(second.verdict()).isEqualTo(Verdict.blocked(BlockReason.DUPLICATE_PAYMENT));
        assertThat(second.payment().entries().getFirst().forward()).isEqualTo(Payment.Forward.NONE);
    }

    /** Without a documented late success, SUCCESS after FAILED is not accepted. */
    @Test
    void lateSuccessNeedsTheProviderPolicy() {
        Validation order = validation(1, "npci", "R1", RS_499);
        PaymentMessage failed =
                message("npci", MessageType.ASYNC_RESPONSE, "m1", "R1", "T1", Outcome.FAILED, RS_499, T0);
        PaymentMessage success =
                message("npci", MessageType.ASYNC_RESPONSE, "m2", "R1", "T1", Outcome.SUCCESS, RS_499, T0);
        Gate.Result first = Gate.receive(failed, order, null, T0, STRICT);

        Gate.Result late = Gate.receive(success, first.validation(), first.payment(), T0, STRICT);

        assertThat(late.verdict()).isEqualTo(Verdict.blocked(BlockReason.INVALID_TRANSITION));
        assertThat(late.validation().status()).isEqualTo(Validation.Status.CREATED);
    }

    private static PaymentMessage refund(String eventId, String refundId, long amount) {
        return new PaymentMessage(
                Ref.of("razorpay"),
                MessageType.WEBHOOK,
                Ref.of(eventId),
                Ref.of("order_X"),
                Ref.of("pay_Y"),
                Outcome.REFUNDED,
                Money.of(amount, "INR"),
                T0,
                T0,
                Ref.of(refundId));
    }

    @Test
    void onlyOutboundCallsArePermitted() {
        Validation order = validation(1, "razorpay", "order_X", RS_499);

        assertThatThrownBy(() -> Gate.permit(
                        razorpay(MessageType.WEBHOOK, "evt_1", "pay_Y", Outcome.SUCCESS, null),
                        order,
                        null,
                        List.of(),
                        T0,
                        Check.Limits.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Gate.permit(
                        razorpay(MessageType.PAY_REQUEST, "aegis:1", "pay_Y", Outcome.PENDING, Money.of(1, "INR")),
                        order,
                        null,
                        List.of(),
                        T0,
                        Check.Limits.NONE))
                .isEqualTo(Verdict.blocked(BlockReason.AMOUNT_MISMATCH));
    }
}
