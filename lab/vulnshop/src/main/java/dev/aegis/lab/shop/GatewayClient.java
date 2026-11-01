package dev.aegis.lab.shop;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates gateway orders. The same Razorpay request goes either straight to the gateway or to Aegis;
 * only the URL and the credential differ (that is the whole Aegis integration on this side).
 */
@Component
class GatewayClient {

    record GatewayOrder(String id, long amount) {}

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ShopProperties properties;
    private final JsonMapper json;

    GatewayClient(ShopProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    GatewayOrder createOrder(String receipt, long amount) {
        String body = json.writeValueAsString(Map.of("amount", amount, "currency", "INR", "receipt", receipt));
        HttpRequest request = HttpRequest.newBuilder(properties.ordersUrl())
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", properties.authorization())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("gateway refused the order: HTTP " + response.statusCode());
            }
            JsonNode order = json.readTree(response.body());
            return new GatewayOrder(
                    order.get("id").asString(), order.get("amount").asLong());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gateway unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
