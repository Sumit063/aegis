package dev.aegis;

import static dev.aegis.Scenario.event;
import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.payment.Validation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Failure injection against the real dependencies (HLD §9): the containers are actually paused.
 * T13 Kafka down at ingress, a database outage under the Checker, and T19 no unrequested provider calls.
 */
class FailureIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ValidationRepository validations;

    @Autowired
    PaymentRepository payments;

    @Autowired
    TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();

    private int webhook(String body) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/razorpay"))
                                .header("X-Razorpay-Signature", Scenario.sign(RAZORPAY_WEBHOOK_SECRET, body))
                                .header("X-Razorpay-Event-Id", unique("evt_"))
                                .timeout(Duration.ofSeconds(15))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private static void pause(GenericContainer<?> container) {
        container
                .getDockerClient()
                .pauseContainerCmd(container.getContainerId())
                .exec();
    }

    private static void resume(GenericContainer<?> container) {
        container
                .getDockerClient()
                .unpauseContainerCmd(container.getContainerId())
                .exec();
    }

    /** T13: with Kafka down nothing is acknowledged (the provider retries); after recovery it is. */
    @Test
    void kafkaDownMeansNoAcknowledgement() throws Exception {
        String orderId = unique("order_");
        Scenario.order(validations, tx, orderId, 49_900);
        String body = event("payment.captured", orderId, unique("pay_"), 49_900, Instant.now());

        pause(KAFKA);
        int during;
        try {
            during = webhook(body);
        } finally {
            resume(KAFKA);
        }

        assertThat(during).isEqualTo(503);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> webhook(body) == 200);
    }

    /** A database outage pauses the Checker; the message waits in Kafka and is processed afterwards. */
    @Test
    void databaseOutageDelaysButLosesNothing() throws Exception {
        String orderId = unique("order_");
        Validation order = Scenario.order(validations, tx, orderId, 49_900);
        String body = event("payment.captured", orderId, unique("pay_"), 49_900, Instant.now());

        pause(POSTGRES);
        try {
            assertThat(webhook(body)).as("Ingress needs only Kafka").isEqualTo(200);
            Thread.sleep(3000);
        } finally {
            resume(POSTGRES);
        }

        await().atMost(Duration.ofSeconds(60))
                .until(() -> validations.find(order.id()).orElseThrow().status() == Validation.Status.PAID);
        assertThat(payments.ofValidation(order.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.entries()).hasSize(1));
    }

    /** T19: inbound traffic never makes Aegis call a provider. */
    @Test
    void inboundTrafficNeverCallsTheProvider() throws Exception {
        int before = FakeProvider.received("/").size();
        String orderId = unique("order_");
        Scenario.order(validations, tx, orderId, 49_900);

        webhook(event("payment.captured", orderId, unique("pay_"), 49_900, Instant.now()));
        webhook(event("payment.failed", orderId, unique("pay_"), 49_900, Instant.now()));
        Thread.sleep(2000);

        assertThat(FakeProvider.received("/")).hasSize(before);
    }
}
