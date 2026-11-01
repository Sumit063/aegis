package dev.aegis.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ingress over real HTTP and Kafka: the provider's answer and what reaches the log. */
class IngressIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    static String event(String paymentId) {
        return """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":{"id":"%s",\
                "amount":49900,"currency":"INR","status":"captured","order_id":"order_X"}}},"created_at":%d}""".formatted(paymentId, Instant.now().getEpochSecond());
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** T1 (ingress half): a genuine webhook is stored, then acknowledged the way Razorpay expects. */
    @Test
    void acknowledgesAGenuineWebhookAfterItIsInTheLog() throws Exception {
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 8);
        String body = event(paymentId);

        HttpResponse<String> response = post(
                "/in/razorpay",
                body,
                Map.of("X-Razorpay-Signature", sign(RAZORPAY_WEBHOOK_SECRET, body), "X-Razorpay-Event-Id", "evt_it"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode record = consume("razorpay:" + paymentId);
        assertThat(record.get("verified").asBoolean()).isTrue();
        assertThat(record.get("sealedBody").asString()).startsWith("enc:v1:").doesNotContain(paymentId);
        assertThat(record.at("/headers/X-Razorpay-Event-Id/0").asString()).isEqualTo("evt_it");
    }

    /** T2 and T3: forged, even claiming to be local, gets 401 and is logged as unverified. */
    @Test
    void refusesAForgeryEvenFromLocalhost() throws Exception {
        String body = event("pay_forged");

        HttpResponse<String> response = post(
                "/in/razorpay",
                body,
                Map.of("X-Razorpay-Signature", sign("guessed-secret-00000000", body), "X-Forwarded-For", "127.0.0.1"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void unknownConnectionAndOversizedBodies() throws Exception {
        assertThat(post("/in/stripe", "{}", Map.of()).statusCode()).isEqualTo(404);
        assertThat(post("/in/razorpay", "x".repeat(70_000), Map.of()).statusCode())
                .isEqualTo(413);
    }

    private JsonNode consume(String key) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "ingress-it-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, byte[]> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("aegis.inbound"));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return json.readTree(record.value());
                    }
                }
            }
        }
        throw new AssertionError("no record with key " + key);
    }
}
