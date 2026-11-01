package dev.aegis.domain.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.Outcome;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Every (current, next) pair of the transition table, permitted and rejected. */
class TransitionsTest {

    private static final Policy LATE_SUCCESS = new Policy(Duration.ofHours(1), true);
    private static final Policy STRICT = new Policy(Duration.ofHours(1), false);

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource(
            nullValues = "NONE",
            value = {
                // from nothing
                "NONE,     PENDING,  OK",
                "NONE,     SUCCESS,  OK",
                "NONE,     FAILED,   OK",
                "NONE,     REFUNDED, INVALID_TRANSITION",
                "NONE,     REVERSED, INVALID_TRANSITION",
                "NONE,     OTHER,    UNSUPPORTED",
                // authorized / in progress
                "PENDING,  PENDING,  DUPLICATE",
                "PENDING,  SUCCESS,  OK",
                "PENDING,  FAILED,   OK",
                "PENDING,  REFUNDED, INVALID_TRANSITION",
                "PENDING,  REVERSED, OK",
                "PENDING,  OTHER,    UNSUPPORTED",
                // captured (a refund depends on its identity, below; refunds never change the state)
                "SUCCESS,  PENDING,  DOWNGRADE",
                "SUCCESS,  SUCCESS,  DUPLICATE",
                "SUCCESS,  FAILED,   DOWNGRADE",
                "SUCCESS,  REVERSED, OK",
                "SUCCESS,  OTHER,    UNSUPPORTED",
                // failed (SUCCESS depends on the provider, below)
                "FAILED,   PENDING,  DOWNGRADE",
                "FAILED,   FAILED,   DUPLICATE",
                "FAILED,   REFUNDED, INVALID_TRANSITION",
                "FAILED,   REVERSED, INVALID_TRANSITION",
                "FAILED,   OTHER,    UNSUPPORTED",
                // reversed
                "REVERSED, PENDING,  DOWNGRADE",
                "REVERSED, SUCCESS,  DOWNGRADE",
                "REVERSED, FAILED,   DOWNGRADE",
                "REVERSED, REFUNDED, INVALID_TRANSITION",
                "REVERSED, REVERSED, DUPLICATE",
                "REVERSED, OTHER,    UNSUPPORTED"
            })
    void everyPair(Outcome current, Outcome next, String expected) {
        assertThat(Transitions.check(current, next, false, STRICT)).isEqualTo(reason(expected));
        assertThat(Transitions.check(current, next, false, LATE_SUCCESS))
                .as("the provider policy changes nothing here")
                .isEqualTo(reason(expected));
    }

    /** Late success after a failure only where the provider documents it (e.g. Razorpay). */
    @ParameterizedTest(name = "late success, provider allows it: {0} → {1}")
    @CsvSource({"true, OK", "false, INVALID_TRANSITION"})
    void successAfterFailureFollowsTheProvider(boolean allowed, String expected) {
        Policy policy = allowed ? LATE_SUCCESS : STRICT;
        assertThat(Transitions.check(Outcome.FAILED, Outcome.SUCCESS, false, policy))
                .isEqualTo(reason(expected));
    }

    /** Each refund is a fact on the captured payment: a new refund passes, the same refund again is a duplicate. */
    @ParameterizedTest(name = "refund of a captured payment, same refund seen: {0} → {1}")
    @CsvSource({"false, OK", "true, DUPLICATE"})
    void refundsAreDistinguishedByIdentity(boolean sameFactSeen, String expected) {
        assertThat(Transitions.check(Outcome.SUCCESS, Outcome.REFUNDED, sameFactSeen, STRICT))
                .isEqualTo(reason(expected));
    }

    /** A refund is not a lifecycle state, so nothing can be "after REFUNDED". */
    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            names = {"REFUNDED", "OTHER"})
    void refundedAndOtherAreNeverAPaymentState(Outcome current) {
        assertThatThrownBy(() -> Transitions.check(current, Outcome.SUCCESS, false, STRICT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Optional<BlockReason> reason(String expected) {
        return expected.equals("OK") ? Optional.empty() : Optional.of(BlockReason.valueOf(expected));
    }
}
