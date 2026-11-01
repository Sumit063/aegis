package dev.aegis.lab.shop;

import dev.aegis.lab.shop.PaymentEvent.Channel;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Direct integration: the gateway's webhook and the browser's Checkout callback reach the shop,
 * which verifies them itself. This is where the flaws in signature handling live.
 */
@RestController
class RazorpayController {

    private final ShopProperties properties;
    private final Flaws flaws;
    private final Shop shop;
    private final JsonMapper json;

    RazorpayController(ShopProperties properties, Flaws flaws, Shop shop, JsonMapper json) {
        this.properties = properties;
        this.flaws = flaws;
        this.shop = shop;
        this.json = json;
    }

    @PostMapping("/webhooks/razorpay")
    ResponseEntity<String> webhook(
            @RequestBody String body,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
            @RequestHeader(value = "Host", required = false) String host) {
        JsonNode event = json.readTree(body);
        String name = event.path("event").asString("");
        if (!webhookTrusted(name, host, body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad signature");
        }
        JsonNode payment = event.at("/payload/payment/entity");
        if (!name.equals("payment.captured") && !name.equals("payment.failed") && !name.equals("order.paid")) {
            return ResponseEntity.ok("ignored: " + name);
        }
        return ResponseEntity.ok(shop.apply(new PaymentEvent(
                Channel.WEBHOOK,
                eventId,
                payment.path("id").asString(null),
                payment.path("order_id").asString(null),
                payment.at("/notes/shop_order").asString(null),
                payment.has("amount") ? payment.get("amount").asLong() : null,
                !name.equals("payment.failed"),
                Instant.ofEpochSecond(event.path("created_at").asLong(0)))));
    }

    @PostMapping("/payment/callback")
    ResponseEntity<String> callback(@RequestParam("order") String shopOrder, @RequestParam Map<String, String> form) {
        String orderId = form.get("razorpay_order_id");
        String paymentId = form.get("razorpay_payment_id");
        boolean trusted = flaws.has(Flaw.F7) && "success".equals(form.get("status"))
                || Hmac.matches(properties.keySecret(), orderId + "|" + paymentId, form.get("razorpay_signature"));
        if (!trusted) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad signature");
        }
        return ResponseEntity.ok(
                shop.apply(new PaymentEvent(Channel.CALLBACK, null, paymentId, orderId, shopOrder, null, true, null)));
    }

    private boolean webhookTrusted(String event, String host, String body, String signature) {
        if (flaws.has(Flaw.F2) && event.equals("order.paid")) {
            return true;
        }
        if (flaws.has(Flaw.F4) && host != null && host.matches("(localhost|127\\.0\\.0\\.1)(:\\d+)?")) {
            return true;
        }
        String secret = flaws.has(Flaw.F1) ? "" : properties.webhookSecret();
        // Fail closed: without F1 an unset secret verifies nothing.
        if (secret == null || (secret.isEmpty() && !flaws.has(Flaw.F1))) {
            return false;
        }
        return Hmac.matches(secret, body, signature);
    }
}
