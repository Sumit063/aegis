package dev.aegis.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void passedHasNoReason() {
        assertThat(Verdict.PASSED.passed()).isTrue();
        assertThat(Verdict.PASSED.reason()).isNull();
        assertThat(Verdict.PASSED).hasToString("PASSED");
    }

    @Test
    void blockedNeedsAReason() {
        Verdict blocked = Verdict.blocked(BlockReason.REPLAY);

        assertThat(blocked.passed()).isFalse();
        assertThat(blocked).hasToString("BLOCKED REPLAY");
        assertThatThrownBy(() -> Verdict.blocked(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void onlyProviderInitiatedMessagesAreForwarded() {
        assertThat(MessageType.WEBHOOK.forwarded()).isTrue();
        assertThat(MessageType.CALLBACK.forwarded()).isTrue();
        assertThat(MessageType.ASYNC_RESPONSE.forwarded()).isTrue();
        assertThat(MessageType.PAY_REQUEST.forwarded()).isFalse();
        assertThat(MessageType.STATUS_CHECK.forwarded()).isFalse();
    }

    @Test
    void staleOutcomesRankBelowLaterOnes() {
        assertThat(Outcome.PENDING.rank()).isLessThan(Outcome.SUCCESS.rank());
        assertThat(Outcome.SUCCESS.rank()).isEqualTo(Outcome.FAILED.rank());
        assertThat(Outcome.REFUNDED.rank()).isGreaterThan(Outcome.SUCCESS.rank());
        assertThat(Outcome.OTHER.rank()).isZero();
    }

    @Test
    void messageNeedsItsIdentityButNotOptionalFields() {
        Instant now = Instant.parse("2026-11-01T10:00:00Z");
        PaymentMessage message = new PaymentMessage(
                Ref.of("razorpay"),
                MessageType.CALLBACK,
                Ref.of("cb:pay_TEST0001"),
                null,
                null,
                Outcome.SUCCESS,
                null,
                null,
                now);

        assertThat(message.amount()).isNull();
        assertThatThrownBy(() -> new PaymentMessage(
                        null,
                        MessageType.WEBHOOK,
                        Ref.of("evt_TEST"),
                        null,
                        null,
                        Outcome.SUCCESS,
                        Money.of(1, "INR"),
                        null,
                        now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentMessage(
                        Ref.of("razorpay"),
                        MessageType.WEBHOOK,
                        Ref.of("evt_TEST"),
                        null,
                        null,
                        Outcome.SUCCESS,
                        null,
                        null,
                        null))
                .isInstanceOf(NullPointerException.class);
    }
}
