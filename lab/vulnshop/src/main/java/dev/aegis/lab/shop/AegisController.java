package dev.aegis.lab.shop;

import dev.aegis.lab.shop.PaymentEvent.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Behind Aegis: verified messages arrive here, signed by Aegis (LLD §8), and go into the same
 * business logic, flaws and all. The shop takes the order from Aegis's {@code merchantRef}, which
 * Aegis bound to the gateway order when the order was created.
 */
@RestController
class AegisController {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final ShopProperties properties;
    private final Shop shop;
    private final JsonMapper json;
    // Aegis delivers at least once with a stable id; an id seen before was already applied (LLD §8).
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    AegisController(ShopProperties properties, Shop shop, JsonMapper json) {
        this.properties = properties;
        this.shop = shop;
        this.json = json;
    }

    @PostMapping("/aegis/messages")
    ResponseEntity<String> message(
            @RequestBody String body,
            @RequestHeader(value = "X-Aegis-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Aegis-Signature", required = false) String signature) {
        if (!fromAegis(timestamp, body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad Aegis signature");
        }
        JsonNode message = json.readTree(body);
        if (!processed.add(message.path("id").asString())) {
            return ResponseEntity.ok("ignored: message already processed");
        }
        if (!"PASSED".equals(message.path("verdict").asString())) {
            return ResponseEntity.ok("ignored: not passed");
        }
        String outcome = message.path("outcome").asString();
        if (!outcome.equals("SUCCESS") && !outcome.equals("FAILED")) {
            return ResponseEntity.ok("ignored: " + outcome);
        }
        boolean callback = message.path("type").asString().equals("CALLBACK");
        JsonNode original = callback
                ? null
                : json.readTree(decode(message.at("/original/body").asString()));
        return ResponseEntity.ok(shop.apply(new PaymentEvent(
                callback ? Channel.CALLBACK : Channel.WEBHOOK,
                header(message.at("/original/headers"), "X-Razorpay-Event-Id"),
                message.path("paymentRef").asString(null),
                message.path("providerRef").asString(null),
                message.path("merchantRef").asString(null),
                message.has("amount") && !message.get("amount").isNull()
                        ? message.get("amount").asLong()
                        : null,
                outcome.equals("SUCCESS"),
                original == null
                        ? null
                        : Instant.ofEpochSecond(original.path("created_at").asLong(0)))));
    }

    /**
     * Where Aegis sends the customer's browser after checking the Checkout result. Display only: the
     * shop fulfils from forwarded messages, never from this redirect.
     */
    @GetMapping("/aegis/return")
    ResponseEntity<String> returned(
            @RequestParam String ref,
            @RequestParam String verdict,
            @RequestParam String payment,
            @RequestParam long ts,
            @RequestParam String signature) {
        boolean recent =
                Duration.between(Instant.ofEpochSecond(ts), Instant.now()).abs().compareTo(Duration.ofMinutes(10)) <= 0;
        boolean genuine = recent
                && Hmac.matches(properties.aegisSecret(), ref + "." + verdict + "." + payment + "." + ts, signature);
        return genuine
                ? ResponseEntity.ok("Order " + ref + ": " + verdict)
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad or expired Aegis result");
    }

    private boolean fromAegis(String timestamp, String body, String signature) {
        if (timestamp == null || signature == null || !signature.startsWith("v1=")) {
            return false;
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        boolean recent = Duration.between(Instant.ofEpochSecond(sentAt), Instant.now())
                        .abs()
                        .compareTo(MAX_AGE)
                <= 0;
        return recent && Hmac.matches(properties.aegisSecret(), timestamp + "." + body, signature.substring(3));
    }

    // HTTP header names are case-insensitive; Aegis forwards them as the provider sent them.
    private static String header(JsonNode headers, String name) {
        return headers.properties().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().asString())
                .findFirst()
                .orElse(null);
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }
}
