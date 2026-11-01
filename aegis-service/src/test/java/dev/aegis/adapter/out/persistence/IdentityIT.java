package dev.aegis.adapter.out.persistence;

import static dev.aegis.Scenario.event;
import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.provider.spi.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** H2: identities the database enforces, whatever the code path (HARDENING §2). */
class IdentityIT extends IntegrationTest {

    @Autowired
    CheckMessageUseCase checker;

    @Autowired
    ValidationRepository validations;

    @Autowired
    EvidenceCipherPort cipher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    private IngressRecord webhook(String body, String eventId) {
        return Scenario.record(
                Channel.SERVER,
                true,
                Map.of("X-Razorpay-Event-Id", eventId),
                cipher.seal(body.getBytes(StandardCharsets.UTF_8)),
                Instant.now());
    }

    /** The same record handled by ten consumers at once (rebalance, redelivery): one entry, one fact. */
    @Test
    void concurrentConsumersStoreOneMessageAndOneFact() throws Exception {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        IngressRecord record =
                webhook(event("payment.captured", orderId, paymentId, 49_900, Instant.now()), unique("evt_"));

        List<Callable<CheckMessageUseCase.Result>> consumers =
                java.util.Collections.nCopies(10, () -> checker.check(record));
        long passed = 0;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<CheckMessageUseCase.Result> result : threads.invokeAll(consumers)) {
                passed += result.get().verdict().passed() && !result.get().repeat() ? 1 : 0;
            }
        }

        Long id = jdbc.queryForObject("select id from payments where payment_ref = ?", Long.class, paymentId);
        assertThat(passed).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_messages where payment_id = ?", Integer.class, id))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from payment_facts where payment_id = ?", Integer.class, id))
                .isOne();
    }

    /** Different event IDs reporting the same fact: two messages, one fact (review item 8). */
    @Test
    void differentEventIdsForTheSameFactMakeOneFact() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        String body = event("payment.captured", orderId, paymentId, 49_900, Instant.now());

        checker.check(webhook(body, unique("evt_")));
        checker.check(webhook(body.replace("payment.captured", "order.paid"), unique("evt_")));

        Long id = jdbc.queryForObject("select id from payments where payment_ref = ?", Long.class, paymentId);
        assertThat(jdbc.queryForObject("select count(*) from payment_messages where payment_id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from payment_facts where payment_id = ?", Integer.class, id))
                .isOne();
    }

    /** Even code that skipped every check could not store a fact twice: the constraint refuses it. */
    @Test
    void theDatabaseRefusesASecondCopyOfAFact() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        checker.check(webhook(event("payment.captured", orderId, paymentId, 49_900, Instant.now()), unique("evt_")));
        Long id = jdbc.queryForObject("select id from payments where payment_ref = ?", Long.class, paymentId);

        assertThatThrownBy(() -> jdbc.update(
                        "insert into payment_facts (payment_id, outcome, fact_ref, n) values (?, 'SUCCESS', '', 9)",
                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        String messageId =
                jdbc.queryForObject("select message_id from payment_messages where payment_id = ?", String.class, id);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into payment_messages (provider, message_id, payment_id, n) values ('razorpay', ?, ?, 9)",
                        messageId,
                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A message ID names one message for the whole provider: seen before anywhere means a repeat. */
    @Test
    void aMessageIdIsUniqueAcrossPayments() {
        String orderId = unique("order_");
        String eventId = unique("evt_");
        Scenario.order(validations, tx, orderId, 49_900);
        CheckMessageUseCase.Result first = checker.check(
                webhook(event("payment.captured", orderId, unique("pay_"), 49_900, Instant.now()), eventId));

        CheckMessageUseCase.Result reused = checker.check(
                webhook(event("payment.captured", orderId, unique("pay_"), 49_900, Instant.now()), eventId));

        assertThat(reused.repeat()).isTrue();
        assertThat(reused.paymentId()).isEqualTo(first.paymentId());
    }
}
