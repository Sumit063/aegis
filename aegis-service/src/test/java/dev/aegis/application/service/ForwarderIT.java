package dev.aegis.application.service;

import static dev.aegis.Scenario.event;
import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.FakeApplication;
import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.payment.Payment;
import dev.aegis.provider.spi.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Forwarder (LLD §8): what the application receives, once, signed, in order, despite outages. */
class ForwarderIT extends IntegrationTest {

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

    @Autowired
    JsonMapper json;

    @AfterEach
    void applicationBackUp() {
        FakeApplication.answer(200);
    }

    private IngressRecord webhook(String body, String eventId) {
        return Scenario.record(
                Channel.SERVER,
                true,
                Map.of("X-Razorpay-Event-Id", eventId, "X-Razorpay-Signature", "sig"),
                cipher.seal(body.getBytes(StandardCharsets.UTF_8)),
                Instant.now());
    }

    private List<JsonNode> deliveredFor(String paymentId) {
        return FakeApplication.received().stream()
                .map(r -> json.readTree(r.body()))
                .filter(m -> paymentId.equals(m.path("paymentRef").asString(null)))
                .toList();
    }

    /** T1 and T4: the original reaches the application once, signed, however often the provider repeats it. */
    @Test
    void deliversTheOriginalOnceSigned() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        String body = event("payment.captured", orderId, paymentId, 49_900, Instant.now());
        IngressRecord captured = webhook(body, unique("evt_"));
        for (int i = 0; i < 5; i++) {
            checker.check(captured);
        }

        await().atMost(Duration.ofSeconds(10))
                .until(() -> deliveredFor(paymentId).size() == 1);
        JsonNode message = deliveredFor(paymentId).getFirst();
        assertThat(message.get("verdict").asString()).isEqualTo("PASSED");
        assertThat(message.get("type").asString()).isEqualTo("WEBHOOK");
        assertThat(message.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(message.get("merchantRef").asString()).isEqualTo("SHOP-" + orderId);
        assertThat(message.get("providerRef").asString()).isEqualTo(orderId);
        assertThat(message.get("amount").asLong()).isEqualTo(49_900);
        assertThat(message.at("/original/headers/X-Razorpay-Event-Id").asString())
                .isNotBlank();
        assertThat(new String(
                        Base64.getDecoder().decode(message.at("/original/body").asString()), StandardCharsets.UTF_8))
                .isEqualTo(body);

        FakeApplication.Received raw = FakeApplication.received().stream()
                .filter(r -> r.body().contains(paymentId))
                .findFirst()
                .orElseThrow();
        assertThat(raw.signature()).isEqualTo("v1=" + Scenario.sign(SHOP_SECRET, raw.timestamp() + "." + raw.body()));
        assertThat(Long.parseLong(raw.timestamp()))
                .isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(60L));

        Long id = jdbc.queryForObject("select id from payments where payment_ref = ?", Long.class, paymentId);
        await().atMost(Duration.ofSeconds(5))
                .until(() ->
                        payments.find(id).orElseThrow().entries().getFirst().forward() == Payment.Forward.DELIVERED);
        assertThat(jdbc.queryForObject("select forward_due_at is null from payments where id = ?", Boolean.class, id))
                .isTrue();
    }

    /** T12: the application is down; messages wait and arrive after it recovers, in order. */
    @Test
    void survivesAnApplicationOutageAndKeepsOrder() {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        FakeApplication.answer(503);

        checker.check(webhook(event("payment.authorized", orderId, paymentId, 49_900, Instant.now()), unique("evt_")));
        checker.check(webhook(event("payment.captured", orderId, paymentId, 49_900, Instant.now()), unique("evt_")));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> FakeApplication.received().stream()
                                .filter(r -> r.body().contains(paymentId))
                                .count()
                        >= 2);
        assertThat(FakeApplication.received().stream()
                        .filter(r -> r.body().contains(paymentId))
                        .map(r -> json.readTree(r.body()).get("outcome").asString())
                        .distinct())
                .as("only the first message is tried while the application is down")
                .containsExactly("PENDING");

        FakeApplication.answer(200);
        await().atMost(Duration.ofSeconds(20))
                .until(() -> deliveredFor(paymentId).stream()
                        .map(m -> m.get("outcome").asString())
                        .distinct()
                        .toList()
                        .equals(List.of("PENDING", "SUCCESS")));
    }

    @Test
    void retriesBackOffAndEventuallyGiveUp() {
        ForwardRepositoryProbe.assertBackoff();
    }
}
