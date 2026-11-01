package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/** LLD §12: what operators can measure, and that logs carry references only. */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Value("${local.management.port}")
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private int webhook(String body, String signature) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/razorpay"))
                                .header("X-Razorpay-Signature", signature)
                                .header("X-Razorpay-Event-Id", unique("evt_"))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    @Test
    void exposesVerdictsAcknowledgementsAndForwardLag() throws Exception {
        String body = Scenario.event("payment.captured", unique("order_"), unique("pay_"), 100, Instant.now());
        webhook(body, "forged");

        String metrics = http.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();

        assertThat(metrics)
                .contains("aegis_ingress_ack_seconds")
                .contains("aegis_forward_lag_seconds")
                .containsPattern("aegis_messages_total\\{.*reason=\"BAD_SIGNATURE\".*\\}");
    }

    /** A caller's request ID is kept (and echoed) so one ID follows the message through the logs. */
    @Test
    void echoesTheCorrelationId() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/razorpay"))
                        .header("X-Request-Id", "trace-12345678")
                        .header("X-Razorpay-Signature", "forged")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-Id")).contains("trace-12345678");
        assertThat(http.send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/razorpay"))
                                        .header("X-Request-Id", "bad id with spaces")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .headers()
                        .firstValue("X-Request-Id")
                        .orElseThrow())
                .as("an unsafe ID is replaced")
                .doesNotContain(" ");
    }

    /** Invariant 9: neither secrets nor message content reach the logs, even for a forgery. */
    @Test
    void logsCarryReferencesOnly(CapturedOutput output) throws Exception {
        String marker = "customer-email-" + unique("x") + "@example.com";
        String body = Scenario.event("payment.captured", unique("order_"), unique("pay_"), 100, Instant.now())
                .replace("\"method\":\"upi\"", "\"email\":\"" + marker + "\"")
                .replace("}}},", ",\"email\":\"" + marker + "\"}}},");

        assertThat(webhook(body, Scenario.sign(RAZORPAY_WEBHOOK_SECRET, body))).isEqualTo(200);
        assertThat(webhook(body, "forged")).isEqualTo(401);
        Thread.sleep(1500);

        assertThat(output.getAll())
                .doesNotContain(marker)
                .doesNotContain(RAZORPAY_WEBHOOK_SECRET)
                .doesNotContain(RAZORPAY_KEY_SECRET)
                .doesNotContain(SHOP_API_KEY)
                .doesNotContain(SHOP_SECRET);
    }
}
