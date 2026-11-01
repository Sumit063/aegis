package dev.aegis.adapter.out.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * One element of {@code payments.messages} (LLD §7): the domain entry plus what is kept of the
 * message and of its delivery to the application. Bodies are ciphertext.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record StoredEntry(
        int n,
        String type,
        String messageId,
        Instant at,
        String outcome,
        String verdict,
        String reason,
        int duplicates,
        Map<String, String> headers,
        String body,
        String reply,
        Integer httpStatus,
        Forward forward) {

    /** Delivery to the application: state, attempts so far, and when to try next. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Forward(String status, int attempts, Instant firstAt, Instant lastAt, Instant nextAt, String error) {

        static Forward of(String status) {
            return new Forward(status, 0, null, null, null, null);
        }

        Forward withStatus(String status) {
            return new Forward(status, attempts, firstAt, lastAt, nextAt, error);
        }
    }

    StoredEntry with(int duplicates, Forward forward) {
        return new StoredEntry(
                n,
                type,
                messageId,
                at,
                outcome,
                verdict,
                reason,
                duplicates,
                headers,
                body,
                reply,
                httpStatus,
                forward);
    }
}
