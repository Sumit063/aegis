package dev.aegis.domain.check;

import static dev.aegis.domain.message.BlockReason.DOWNGRADE;
import static dev.aegis.domain.message.BlockReason.DUPLICATE;
import static dev.aegis.domain.message.BlockReason.INVALID_TRANSITION;
import static dev.aegis.domain.message.BlockReason.UNSUPPORTED;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.Outcome;
import java.util.Optional;

/**
 * Which outcome may follow which, for one payment (one provider transaction). Explicit, so every
 * pair is decided on purpose and tested; there is no ordering of outcomes to reason from.
 *
 * <p>The current state is the payment's lifecycle: PENDING, SUCCESS (captured), FAILED or
 * REVERSED. A refund is not a state: it is a fact recorded on a captured payment, which stays
 * captured however much is refunded (the cumulative amount is checked by
 * {@link Checks#REFUND_WITHIN_PAYMENT}). So a provider confirming "captured" after a partial refund
 * is consistent, not a downgrade.
 *
 * <pre>
 * current \ next  PENDING      SUCCESS            FAILED      REFUNDED            REVERSED
 * (none)          ok           ok                 ok          INVALID             INVALID
 * PENDING         DUPLICATE    ok                 ok          INVALID             ok
 * SUCCESS         DOWNGRADE    DUPLICATE          DOWNGRADE   ok / DUPLICATE**    ok
 * FAILED          DOWNGRADE    policy*            DUPLICATE   INVALID             INVALID
 * REVERSED        DOWNGRADE    DOWNGRADE          DOWNGRADE   INVALID             DUPLICATE
 * </pre>
 *
 * * SUCCESS after FAILED only where the provider documents late success ({@link Policy}); otherwise
 * INVALID_TRANSITION. ** each refund is its own fact (its fact reference); the same refund again is
 * a DUPLICATE. OTHER is always UNSUPPORTED.
 */
public final class Transitions {

    private Transitions() {}

    /**
     * @param current the payment's lifecycle state, or null if nothing has passed yet
     * @param sameFactSeen a passed message already reported this outcome with this fact reference
     */
    public static Optional<BlockReason> check(Outcome current, Outcome next, boolean sameFactSeen, Policy policy) {
        if (next == Outcome.OTHER) {
            return Optional.of(UNSUPPORTED);
        }
        BlockReason reason = current == null
                ? fromNothing(next)
                : switch (current) {
                    case PENDING ->
                        switch (next) {
                            case PENDING -> DUPLICATE;
                            case SUCCESS, FAILED, REVERSED -> null;
                            case REFUNDED -> INVALID_TRANSITION;
                            case OTHER -> UNSUPPORTED;
                        };
                    case SUCCESS ->
                        switch (next) {
                            case PENDING, FAILED -> DOWNGRADE;
                            case SUCCESS -> DUPLICATE;
                            case REFUNDED -> sameFactSeen ? DUPLICATE : null;
                            case REVERSED -> null;
                            case OTHER -> UNSUPPORTED;
                        };
                    case FAILED ->
                        switch (next) {
                            case PENDING -> DOWNGRADE;
                            case SUCCESS -> policy.lateSuccessAfterFailure() ? null : INVALID_TRANSITION;
                            case FAILED -> DUPLICATE;
                            case REFUNDED, REVERSED -> INVALID_TRANSITION;
                            case OTHER -> UNSUPPORTED;
                        };
                    case REVERSED ->
                        switch (next) {
                            case PENDING, SUCCESS, FAILED -> DOWNGRADE;
                            case REFUNDED -> INVALID_TRANSITION;
                            case REVERSED -> DUPLICATE;
                            case OTHER -> UNSUPPORTED;
                        };
                    case REFUNDED, OTHER -> throw new IllegalArgumentException("not a payment state: " + current);
                };
        return Optional.ofNullable(reason);
    }

    private static BlockReason fromNothing(Outcome next) {
        return switch (next) {
            case PENDING, SUCCESS, FAILED -> null;
            case REFUNDED, REVERSED -> INVALID_TRANSITION;
            case OTHER -> UNSUPPORTED;
        };
    }
}
