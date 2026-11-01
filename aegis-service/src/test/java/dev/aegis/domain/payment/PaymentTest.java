package dev.aegis.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment.Forward;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Ref RAZORPAY = Ref.of("razorpay");
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static PaymentMessage message(MessageType type, String id, Outcome outcome) {
        return new PaymentMessage(
                RAZORPAY, type, Ref.of(id), Ref.of("order_TEST"), Ref.of("pay_TEST"), outcome, null, null, NOW);
    }

    @Test
    void appendsEntriesInOrderAndForwardsOnlyPassedProviderMessages() {
        Payment payment = Payment.open(1L, RAZORPAY, Ref.of("pay_TEST"))
                .append(message(MessageType.CALLBACK, "cb:pay_TEST", Outcome.SUCCESS), Verdict.PASSED)
                .append(message(MessageType.WEBHOOK, "evt_1", Outcome.SUCCESS), Verdict.blocked(BlockReason.DUPLICATE))
                .append(message(MessageType.STATUS_CHECK, "aegis:1", Outcome.SUCCESS), Verdict.PASSED);

        assertThat(payment.entries()).extracting(Payment.Entry::n).containsExactly(1, 2, 3);
        assertThat(payment.entries())
                .extracting(Payment.Entry::forward)
                .containsExactly(Forward.PENDING, Forward.NONE, Forward.NONE);
        assertThat(payment.status()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void blockedAndUninterpretedMessagesLeaveTheStatusAlone() {
        Payment payment = Payment.open(1L, RAZORPAY, Ref.of("pay_TEST"))
                .append(message(MessageType.WEBHOOK, "evt_1", Outcome.SUCCESS), Verdict.PASSED)
                .append(message(MessageType.WEBHOOK, "evt_2", Outcome.FAILED), Verdict.blocked(BlockReason.DOWNGRADE))
                .append(message(MessageType.WEBHOOK, "evt_3", Outcome.OTHER), Verdict.PASSED);

        assertThat(payment.status()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void aPaymentRequestIsInFlightUntilAFinalAnswerPasses() {
        Payment sent = Payment.open(2L, Ref.of("bbps"), Ref.of("T1"))
                .append(message(MessageType.PAY_REQUEST, "aegis:req", Outcome.PENDING), Verdict.PASSED);

        assertThat(sent.isInFlight()).isTrue();
        assertThat(sent.status()).as("the acknowledgement is not news").isNull();
        assertThat(Payment.open(1L, RAZORPAY, Ref.of("pay_TEST")).isInFlight()).isFalse();
        assertThat(sent.append(message(MessageType.ASYNC_RESPONSE, "npci:1", Outcome.SUCCESS), Verdict.PASSED)
                        .isInFlight())
                .isFalse();
    }

    @Test
    void aRepeatOnlyIncreasesTheCounter() {
        Payment payment = Payment.open(1L, RAZORPAY, Ref.of("pay_TEST"))
                .append(message(MessageType.WEBHOOK, "evt_1", Outcome.SUCCESS), Verdict.PASSED);

        Payment repeated = payment.countRepeat(Ref.of("evt_1")).countRepeat(Ref.of("evt_1"));

        assertThat(repeated.entries()).hasSize(1);
        assertThat(repeated.findEntry(Ref.of("evt_1")).orElseThrow().duplicates())
                .isEqualTo(2);
        assertThat(repeated.findEntry(Ref.of("evt_9"))).isEmpty();
    }

    @Test
    void untrustedMessagesGetTheirOwnPaymentWithAnUnverifiedId() {
        Payment forged =
                Payment.untrusted(RAZORPAY, MessageType.WEBHOOK, BlockReason.BAD_SIGNATURE, NOW, UUID.randomUUID());

        assertThat(forged.validationId()).isNull();
        assertThat(forged.paymentRef()).isNull();
        assertThat(forged.entries()).singleElement().satisfies(e -> {
            assertThat(e.messageId().isUnverified()).isTrue();
            assertThat(e.verdict()).isEqualTo(Verdict.blocked(BlockReason.BAD_SIGNATURE));
        });
    }

    @Test
    void filtersEntriesByType() {
        Payment payment = Payment.open(1L, RAZORPAY, Ref.of("pay_TEST"))
                .append(message(MessageType.STATUS_CHECK, "aegis:1", Outcome.PENDING), Verdict.PASSED)
                .append(message(MessageType.WEBHOOK, "evt_1", Outcome.SUCCESS), Verdict.PASSED);

        assertThat(payment.entries(MessageType.STATUS_CHECK)).hasSize(1);
    }

    @Test
    void validationIsPayableOnlyWhileCreated() {
        Validation created = new Validation(
                1L,
                RAZORPAY,
                Ref.of("SHOP-1042"),
                Ref.of("order_TEST"),
                Money.of(49_900, "INR"),
                Validation.Status.CREATED);

        assertThat(created.isPayable()).isTrue();
        assertThat(created.markPaid().isPayable()).isFalse();
        assertThat(created.markPaid().markPaid()).isEqualTo(created.markPaid());
        assertThat(new Validation(2L, RAZORPAY, Ref.of("SHOP-1043"), null, null, Validation.Status.FAILED).isPayable())
                .isFalse();
        assertThatThrownBy(
                        () -> new Validation(3L, RAZORPAY, Ref.of("SHOP-1044"), null, null, Validation.Status.CREATED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
