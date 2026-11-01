package dev.aegis.lab.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The direct integration's signature handling, with and without each signature flaw. */
class RazorpayControllerTest {

    private static final String KEY_SECRET = "key-secret-for-tests-only-000000001";
    private static final String WEBHOOK_SECRET = "webhook-secret-for-tests-only-00001";

    private static RazorpayController controller(Flaw... flaws) {
        ShopProperties properties =
                new ShopProperties(null, null, KEY_SECRET, WEBHOOK_SECRET, null, Set.of(flaws), Duration.ofDays(7));
        Shop shop = new Shop(properties);
        shop.add(49_900, "order_A", "SHOP-1");
        return new RazorpayController(properties, shop, JsonMapper.builder().build());
    }

    private static String event(String name) {
        return """
                {"event":"%s","created_at":%d,"payload":{"payment":{"entity":
                {"id":"pay_A","order_id":"order_A","amount":49900,"status":"captured"}}}}""".formatted(name, Instant.now().getEpochSecond());
    }

    private static int webhook(RazorpayController controller, String body, String signature, String host) {
        return controller
                .webhook(body, signature, "evt_1", host)
                .getStatusCode()
                .value();
    }

    @Test
    void signedWebhookIsAccepted() {
        String body = event("payment.captured");
        assertThat(webhook(controller(), body, Hmac.sha256Hex(WEBHOOK_SECRET, body), "shop"))
                .isEqualTo(200);
        assertThat(webhook(controller(), body, "forged", "shop")).isEqualTo(401);
    }

    @Test
    void f1AcceptsAnEmptyKeySignature() {
        String body = event("payment.captured");
        String emptyKey = Hmac.sha256Hex("", body);
        assertThat(webhook(controller(), body, emptyKey, "shop")).isEqualTo(401);
        assertThat(webhook(controller(Flaw.F1), body, emptyKey, "shop")).isEqualTo(200);
    }

    @Test
    void f2SkipsVerificationForOrderPaid() {
        String body = event("order.paid");
        assertThat(webhook(controller(), body, null, "shop")).isEqualTo(401);
        assertThat(webhook(controller(Flaw.F2), body, null, "shop")).isEqualTo(200);
    }

    @Test
    void f4TrustsLocalhost() {
        String body = event("payment.captured");
        assertThat(webhook(controller(), body, null, "localhost")).isEqualTo(401);
        assertThat(webhook(controller(Flaw.F4), body, null, "localhost:8091")).isEqualTo(200);
    }

    @Test
    void f7TrustsAStatusField() {
        Map<String, String> form =
                Map.of("razorpay_order_id", "order_A", "razorpay_payment_id", "pay_A", "status", "success");
        assertThat(controller().callback("SHOP-1", form).getStatusCode().value())
                .isEqualTo(401);
        assertThat(controller(Flaw.F7).callback("SHOP-1", form).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void signedCallbackIsAccepted() {
        Map<String, String> form = Map.of(
                "razorpay_order_id", "order_A",
                "razorpay_payment_id", "pay_A",
                "razorpay_signature", Hmac.sha256Hex(KEY_SECRET, "order_A|pay_A"));
        assertThat(controller().callback("SHOP-1", form).getBody()).isEqualTo("fulfilled SHOP-1");
    }
}
