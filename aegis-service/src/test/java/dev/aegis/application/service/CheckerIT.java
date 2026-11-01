package dev.aegis.application.service;

import static dev.aegis.Scenario.event;
import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.in.CheckMessageUseCase.Result;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.provider.spi.Channel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The Checker against real PostgreSQL and Kafka: LLD §3 steps 6–14 and §9. */
class CheckerIT extends IntegrationTest {

    @Autowired
    CheckMessageUseCase checker;

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

    @LocalServerPort
    int port;

    private IngressRecord webhook(String body, String eventId, Instant at) {
        return Scenario.record(
                Channel.SERVER,
                true,
                Map.of("X-Razorpay-Event-Id", eventId),
                cipher.seal(body.getBytes(StandardCharsets.UTF_8)),
                at);
    }

    private IngressRecord callback(String orderId, String paymentId) {
        String body = Scenario.callback(orderId, paymentId, RAZORPAY_KEY_SECRET);
        return Scenario.record(
                Channel.BROWSER, true, Map.of(), cipher.seal(body.getBytes(StandardCharsets.UTF_8)), Instant.now());
    }

    /** T1 end to end: provider → Ingress → Kafka → Checker → PostgreSQL, ready to forward. */
    @Test
    void aGenuineWebhookPassesAndIsQueuedForTheApplication() throws Exception {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Validation order = Scenario.order(validations, tx, orderId, 49_900);
        String body = event("payment.captured", orderId, paymentId, 49_900, Instant.now());

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/razorpay"))
                                .header("X-Razorpay-Signature", Scenario.sign(RAZORPAY_WEBHOOK_SECRET, body))
                                .header("X-Razorpay-Event-Id", unique("evt_"))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(20))
                .until(() -> validations.find(order.id()).orElseThrow().status() == Validation.Status.PAID);
        Payment payment = payments.ofValidation(order.id()).getFirst();
        assertThat(payment.entries()).singleElement().satisfies(e -> {
            assertThat(e.verdict()).isEqualTo(Verdict.PASSED);
            // Queued for the application; the Forwarder may already have delivered it.
            assertThat(e.forward()).isIn(Payment.Forward.PENDING, Payment.Forward.DELIVERED);
        });
    }

    /** LLD §9 rows 3–6: callback passes; the webhook for the same fact is DUPLICATE; retries are counted; a late failure is DOWNGRADE. */
    @Test
    void razorpayPurchase() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);

        Result callback = checker.check(callback(orderId, paymentId));
        IngressRecord captured =
                webhook(event("payment.captured", orderId, paymentId, 49_900, Instant.now()), "evt_c", Instant.now());
        Result webhook = checker.check(captured);
        for (int i = 0; i < 3; i++) {
            assertThat(checker.check(captured).repeat()).isTrue();
        }
        Result lateFailure = checker.check(webhook(
                event("payment.failed", orderId, unique("pay_A"), 49_900, Instant.now()), "evt_f", Instant.now()));

        assertThat(callback.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(callback.merchantRef()).isEqualTo("SHOP-" + orderId);
        assertThat(webhook.verdict()).isEqualTo(Verdict.blocked(BlockReason.DUPLICATE));
        assertThat(webhook.paymentId()).isEqualTo(callback.paymentId());
        assertThat(payments.find(callback.paymentId())
                        .orElseThrow()
                        .entries()
                        .get(1)
                        .duplicates())
                .isEqualTo(3);
        assertThat(lateFailure.verdict()).isEqualTo(Verdict.blocked(BlockReason.DOWNGRADE));
    }

    /** T2, T6, T7, T8 and unparseable messages. */
    @Test
    void blocksWhatCannotBeTrusted() {
        String orderId = unique("order_");
        Scenario.order(validations, tx, orderId, 1_000_000);

        Result forged = checker.check(Scenario.record(
                Channel.SERVER, false, Map.of(), cipher.seal("{}".getBytes(StandardCharsets.UTF_8)), Instant.now()));
        Result old = checker.check(webhook(
                event(
                        "payment.captured",
                        orderId,
                        unique("pay_"),
                        1_000_000,
                        Instant.now().minus(Duration.ofDays(30))),
                unique("evt_"),
                Instant.now()));
        Result unknown = checker.check(webhook(
                event("payment.captured", unique("order_"), unique("pay_"), 1_000, Instant.now()),
                unique("evt_"),
                Instant.now()));
        Result cheaper = checker.check(webhook(
                event("payment.captured", orderId, unique("pay_"), 1_000, Instant.now()),
                unique("evt_"),
                Instant.now()));
        Result garbage = checker.check(webhook("<html>", unique("evt_"), Instant.now()));

        assertThat(forged.verdict()).isEqualTo(Verdict.blocked(BlockReason.BAD_SIGNATURE));
        assertThat(payments.find(forged.paymentId()).orElseThrow().paymentRef()).isNull();
        assertThat(old.verdict()).isEqualTo(Verdict.blocked(BlockReason.REPLAY));
        assertThat(unknown.verdict()).isEqualTo(Verdict.blocked(BlockReason.NO_VALIDATION));
        assertThat(cheaper.verdict()).isEqualTo(Verdict.blocked(BlockReason.AMOUNT_MISMATCH));
        assertThat(garbage.verdict()).isEqualTo(Verdict.blocked(BlockReason.UNPARSEABLE));
    }

    /** T14: a record processed again (offset not yet committed) is a repeat, not a second entry. */
    @Test
    void redeliveryIsARepeat() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        IngressRecord record = webhook(
                event("payment.captured", orderId, paymentId, 49_900, Instant.now()), unique("evt_"), Instant.now());

        Result first = checker.check(record);
        Result again = checker.check(record);

        assertThat(first.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(again.repeat()).isTrue();
        assertThat(payments.find(first.paymentId()).orElseThrow().entries()).hasSize(1);
    }

    /** T15: callback and webhook processed at the same moment: exactly one fact passes. */
    @Test
    void concurrentChannelsPassOneFact() throws Exception {
        for (int round = 0; round < 5; round++) {
            String orderId = unique("order_");
            String paymentId = unique("pay_");
            Scenario.order(validations, tx, orderId, 49_900);
            List<Callable<Result>> both = List.of(
                    () -> checker.check(callback(orderId, paymentId)),
                    () -> checker.check(webhook(
                            event("payment.captured", orderId, paymentId, 49_900, Instant.now()),
                            unique("evt_"),
                            Instant.now())));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Result>> results = executor.invokeAll(both);
                long passed = 0;
                for (Future<Result> result : results) {
                    passed += result.get().verdict().passed() ? 1 : 0;
                }
                assertThat(passed).as("round %d", round).isEqualTo(1);
            }
        }
    }

    /** Two partial refunds are two facts, both forwarded; one more than was paid is blocked (H1). */
    @Test
    void partialRefundsArePassedEachOnce() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        checker.check(webhook(
                event("payment.captured", orderId, paymentId, 49_900, Instant.now()), unique("evt_"), Instant.now()));

        Result first = checker.check(webhook(refund(paymentId, "rfnd_1", 20_000), unique("evt_"), Instant.now()));
        Result sameAgain = checker.check(webhook(refund(paymentId, "rfnd_1", 20_000), unique("evt_"), Instant.now()));
        Result second = checker.check(webhook(refund(paymentId, "rfnd_2", 29_900), unique("evt_"), Instant.now()));
        Result tooMuch = checker.check(webhook(refund(paymentId, "rfnd_3", 1), unique("evt_"), Instant.now()));

        assertThat(first.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(sameAgain.verdict()).isEqualTo(Verdict.blocked(BlockReason.DUPLICATE));
        assertThat(second.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(tooMuch.verdict()).isEqualTo(Verdict.blocked(BlockReason.AMOUNT_MISMATCH));
        Payment payment = payments.find(first.paymentId()).orElseThrow();
        assertThat(payment.wasCaptured()).isTrue();
        assertThat(payment.entries()).filteredOn(e -> e.verdict().passed()).hasSize(3);
    }

    private static String refund(String paymentId, String refundId, long amount) {
        return """
                {"entity":"event","event":"refund.processed","contains":["refund"],"payload":{"refund":{"entity":\
                {"id":"%s","payment_id":"%s","amount":%d,"currency":"INR"}}},"created_at":%d}""".formatted(refundId, paymentId, amount, Instant.now().getEpochSecond());
    }
}
