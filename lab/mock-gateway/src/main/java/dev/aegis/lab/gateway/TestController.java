package dev.aegis.lab.gateway;

import dev.aegis.lab.gateway.GatewayProperties.Account;
import dev.aegis.lab.gateway.Model.Event;
import dev.aegis.lab.gateway.Model.Payment;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controls, not part of Razorpay's API: act as the customer paying in Checkout, re-deliver
 * a webhook (a provider retry), and read an event's exact signed bytes (what an attacker who saw a
 * log or a proxy would have).
 */
@RestController
@RequestMapping("/test")
class TestController {

    /**
     * @param orderId null to pay without an order (Checkout opened with only the public key)
     * @param outcome {@code captured} (default) or {@code failed}
     * @param webhook false to skip the webhook
     * @param createdAt epoch seconds, to make a payment that happened in the past
     */
    record Checkout(
            String keyId,
            String orderId,
            Long amount,
            String currency,
            Map<String, String> notes,
            String outcome,
            Boolean webhook,
            Long createdAt) {}

    /** What the browser receives from Checkout, plus what happened to the webhook. */
    record CheckoutResult(
            String razorpayPaymentId,
            String razorpayOrderId,
            String razorpaySignature,
            String status,
            String eventId,
            int webhookStatus) {}

    record CapturedEvent(Map<String, String> headers, String body) {}

    record Delivery(int webhookStatus) {}

    private final GatewayProperties properties;
    private final Ledger ledger;
    private final Webhooks webhooks;

    TestController(GatewayProperties properties, Ledger ledger, Webhooks webhooks) {
        this.properties = properties;
        this.ledger = ledger;
        this.webhooks = webhooks;
    }

    @PostMapping("/checkout")
    CheckoutResult checkout(@RequestBody Checkout body) {
        Account account = account(body.keyId());
        boolean captured = !"failed".equals(body.outcome());
        long createdAt = body.createdAt() == null ? Instant.now().getEpochSecond() : body.createdAt();
        Payment payment =
                ledger.pay(account, body.orderId(), body.amount(), body.currency(), body.notes(), captured, createdAt);
        // Checkout signs order_id|payment_id with the key secret, only for a successful order payment.
        String signature = captured && payment.orderId() != null
                ? Hmac.sha256Hex(account.keySecret(), payment.orderId() + "|" + payment.id())
                : null;
        Event event = webhooks.create(account, payment);
        int status = Boolean.FALSE.equals(body.webhook()) ? 0 : webhooks.deliver(account, event);
        if (!Boolean.FALSE.equals(body.webhook()) && (status < 200 || status >= 300)) {
            webhooks.retry(account, event, properties.webhookRetry());
        }
        return new CheckoutResult(payment.id(), payment.orderId(), signature, payment.status(), event.id(), status);
    }

    @PostMapping("/events/{id}/redeliver")
    Delivery redeliver(@PathVariable String id) {
        Event event = event(id);
        return new Delivery(webhooks.deliver(account(event.keyId()), event));
    }

    @GetMapping("/events/{id}")
    CapturedEvent captured(@PathVariable String id) {
        Event event = event(id);
        return new CapturedEvent(
                Map.of("X-Razorpay-Signature", event.signature(), "X-Razorpay-Event-Id", event.id()), event.body());
    }

    private Event event(String id) {
        return webhooks.find(id).orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "No such event"));
    }

    private Account account(String keyId) {
        return properties
                .account(keyId)
                .orElseThrow(() -> new GatewayException(HttpStatus.BAD_REQUEST, "Unknown key id"));
    }
}
