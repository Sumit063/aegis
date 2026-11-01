package dev.aegis.application.port.in;

import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.domain.message.Verdict;

/**
 * The Checker (LLD §3 steps 6–14): every check that needs the records, then the message is stored
 * with its verdict, in one transaction under the validation's lock.
 */
public interface CheckMessageUseCase {

    Result check(IngressRecord record);

    /**
     * @param paymentId the payment row the message was stored in
     * @param merchantRef the application's reference for the validation, or null if none matched
     * @param repeat the same message was already stored; only its counter went up
     */
    record Result(Verdict verdict, long paymentId, String merchantRef, boolean repeat) {}
}
