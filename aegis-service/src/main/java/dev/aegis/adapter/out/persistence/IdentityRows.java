package dev.aegis.adapter.out.persistence;

import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.payment.Payment;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes the identity rows of new entries in the caller's transaction (V2). A duplicate violates a
 * primary key and fails the whole transaction: the database, not only the code, guarantees that a
 * message ID exists once and a forwardable fact passes once.
 */
@Component
class IdentityRows {

    private final JdbcTemplate jdbc;

    IdentityRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> paymentOf(String provider, String messageId) {
        return jdbc
                .query(
                        "SELECT payment_id FROM payment_messages WHERE provider = ? AND message_id = ?",
                        (rs, i) -> rs.getLong(1),
                        provider,
                        messageId)
                .stream()
                .findFirst();
    }

    void insert(long paymentId, String provider, Payment.Entry entry) {
        jdbc.update(
                "INSERT INTO payment_messages (provider, message_id, payment_id, n) VALUES (?, ?, ?, ?)",
                provider,
                entry.messageId().value(),
                paymentId,
                entry.n());
        if (isForwardableFact(entry)) {
            jdbc.update(
                    "INSERT INTO payment_facts (payment_id, outcome, fact_ref, n) VALUES (?, ?, ?, ?)",
                    paymentId,
                    entry.outcome().name(),
                    entry.factRef() == null ? "" : entry.factRef().value(),
                    entry.n());
        }
    }

    // Only passed facts that reach the application must be unique; a status-check reply confirming
    // a state is an answer, not a second fact.
    private static boolean isForwardableFact(Payment.Entry entry) {
        return entry.verdict().passed() && entry.type().forwarded() && entry.outcome() != Outcome.OTHER;
    }
}
