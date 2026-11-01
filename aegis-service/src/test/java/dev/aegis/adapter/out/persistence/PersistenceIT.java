package dev.aegis.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.IntegrationTest;
import dev.aegis.application.port.out.Evidence;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The persistence adapters against real PostgreSQL: one row per payment, locks, JSON, evidence. */
class PersistenceIT extends IntegrationTest {

    private static final Ref RAZORPAY = Ref.of("razorpay");

    @Autowired
    ValidationRepository validations;

    @Autowired
    PaymentRepository payments;

    @Autowired
    EvidenceCipherPort cipher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    private Validation validation(String orderId) {
        Validation created = new Validation(
                null,
                RAZORPAY,
                Ref.of("SHOP-" + orderId),
                Ref.of(orderId),
                Money.of(49_900, "INR"),
                Validation.Status.CREATED);
        Evidence exchange = new Evidence(
                Map.of("Content-Type", "application/json"),
                seal("{\"amount\":49900}"),
                seal("{\"id\":\"" + orderId + "\"}"),
                200);
        return tx.execute(s -> validations.insert(created, exchange, Instant.now(), Instant.now()));
    }

    private static PaymentMessage webhook(String eventId, String orderId, String paymentId, Outcome outcome) {
        return new PaymentMessage(
                RAZORPAY,
                MessageType.WEBHOOK,
                Ref.of(eventId),
                Ref.of(orderId),
                Ref.of(paymentId),
                outcome,
                Money.of(49_900, "INR"),
                Instant.now(),
                Instant.now());
    }

    private String seal(String text) {
        return cipher.seal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void storesAValidationWithItsWholeCallAndLocksItByProviderRef() {
        String orderId = unique("order_");
        Validation stored = validation(orderId);

        assertThat(stored.id()).isNotNull();
        Validation paid = tx.execute(s -> {
            Validation locked = validations.lock(RAZORPAY, Ref.of(orderId)).orElseThrow();
            validations.update(locked.markPaid());
            return locked;
        });
        assertThat(paid.amount()).isEqualTo(Money.of(49_900, "INR"));
        assertThat(validations.find(stored.id()).orElseThrow().status()).isEqualTo(Validation.Status.PAID);
        assertThat(jdbc.queryForObject(
                        "select data->'response'->>'body' from validations where id = ?", String.class, stored.id()))
                .startsWith("enc:v1:k1:");
    }

    @Test
    void onePaymentHasOneRowAndKeepsEveryMessageWithItsEvidence() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Validation validation = validation(orderId);
        PaymentMessage captured = webhook("evt_1", orderId, paymentId, Outcome.SUCCESS);

        Payment first = tx.execute(s -> {
            Payment row = payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id());
            return payments.save(
                    row.append(captured, Verdict.PASSED),
                    Map.of(captured.id(), Evidence.of(Map.of("X-Razorpay-Event-Id", "evt_1"), seal("body-1"))));
        });
        Payment second = tx.execute(s -> {
            Payment row = payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id());
            PaymentMessage again = webhook("evt_2", orderId, paymentId, Outcome.SUCCESS);
            return payments.save(
                    row.countRepeat(captured.id()).append(again, Verdict.blocked(BlockReason.DUPLICATE)),
                    Map.of(again.id(), Evidence.of(Map.of(), seal("body-2"))));
        });

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(Outcome.SUCCESS);
        assertThat(second.entries()).hasSize(2);
        assertThat(second.entries().getFirst().duplicates()).isEqualTo(1);
        assertThat(second.entries().get(1).verdict()).isEqualTo(Verdict.blocked(BlockReason.DUPLICATE));
        assertThat(jdbc.queryForObject("select count(*) from payments where payment_ref = ?", Integer.class, paymentId))
                .isEqualTo(1);
        String firstBody =
                jdbc.queryForObject("select messages->0->>'body' from payments where id = ?", String.class, first.id());
        assertThat(new String(cipher.open(firstBody), StandardCharsets.UTF_8)).isEqualTo("body-1");
        assertThat(jdbc.queryForObject(
                        "select messages->0->'headers'->>'X-Razorpay-Event-Id' from payments where id = ?",
                        String.class,
                        first.id()))
                .isEqualTo("evt_1");
        assertThat(payments.ofValidation(validation.id()))
                .extracting(Payment::id)
                .containsExactly(first.id());
    }

    @Test
    void marksThePaymentDueForForwardingOnlyWhileAPassedMessageWaits() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Validation validation = validation(orderId);
        PaymentMessage captured = webhook("evt_f", orderId, paymentId, Outcome.SUCCESS);

        Payment saved = tx.execute(s -> payments.save(
                payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id())
                        .append(captured, Verdict.PASSED),
                Map.of()));
        assertThat(jdbc.queryForObject(
                        "select forward_due_at is not null from payments where id = ?", Boolean.class, saved.id()))
                .isTrue();

        PaymentMessage late = webhook("evt_g", orderId, paymentId, Outcome.FAILED);
        Payment blocked = tx.execute(s -> {
            Payment row = payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id());
            Payment delivered = new Payment(
                    row.id(),
                    row.validationId(),
                    row.provider(),
                    row.paymentRef(),
                    row.status(),
                    row.entries().stream()
                            .map(e -> new Payment.Entry(
                                    e.n(),
                                    e.type(),
                                    e.messageId(),
                                    e.at(),
                                    e.outcome(),
                                    e.verdict(),
                                    e.duplicates(),
                                    Payment.Forward.DELIVERED))
                            .toList());
            return payments.save(delivered.append(late, Verdict.blocked(BlockReason.DOWNGRADE)), Map.of());
        });
        assertThat(blocked.entries().getFirst().forward()).isEqualTo(Payment.Forward.DELIVERED);
        assertThat(jdbc.queryForObject(
                        "select forward_due_at is null from payments where id = ?", Boolean.class, saved.id()))
                .isTrue();
    }

    @Test
    void anUntrustedMessageGetsARowOfItsOwn() {
        Payment untrusted = Payment.untrusted(
                RAZORPAY, MessageType.WEBHOOK, BlockReason.BAD_SIGNATURE, Instant.now(), UUID.randomUUID());

        Payment stored = tx.execute(s -> payments.insertUntrusted(untrusted, Evidence.of(Map.of(), seal("forged"))));

        assertThat(stored.id()).isNotNull();
        assertThat(stored.paymentRef()).isNull();
        assertThat(stored.validationId()).isNull();
        assertThat(stored.entries().getFirst().messageId().isUnverified()).isTrue();
        assertThatThrownBy(() -> payments.insertUntrusted(stored, Evidence.of(Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The row lock serialises checks: a second transaction waits until the first commits. */
    @Test
    void aLockedPaymentMakesTheNextTransactionWait() throws Exception {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Validation validation = validation(orderId);
        tx.executeWithoutResult(s -> payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id()));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong secondGotItAt = new AtomicLong();
        AtomicLong firstReleasedAt = new AtomicLong();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id());
            locked.countDown();
            await(release);
            firstReleasedAt.set(System.nanoTime());
        }));
        locked.await(5, TimeUnit.SECONDS);
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            payments.lockOrCreate(RAZORPAY, Ref.of(paymentId), validation.id());
            secondGotItAt.set(System.nanoTime());
        }));
        Thread.sleep(300);
        assertThat(second).isNotDone();
        release.countDown();
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertThat(secondGotItAt.get()).isGreaterThan(firstReleasedAt.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
