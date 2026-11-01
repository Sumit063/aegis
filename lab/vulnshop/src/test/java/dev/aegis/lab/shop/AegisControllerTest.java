package dev.aegis.lab.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The Aegis receiving side: Aegis's signature and timestamp (LLD §8), then the shop's own logic. */
class AegisControllerTest {

    private static final String AEGIS_SECRET = "aegis-secret-for-tests-only-0000001";

    private static AegisController controller(Shop shop) {
        return new AegisController(
                new ShopProperties(null, null, null, null, AEGIS_SECRET, Set.of(), Duration.ofDays(7)),
                shop,
                JsonMapper.builder().build());
    }

    private static Shop shop() {
        Shop shop = new Shop(
                new ShopProperties(null, null, null, null, null, Set.of(), Duration.ofDays(7)), new Flaws(Set.of()));
        shop.add(49_900, "order_A", "SHOP-1");
        return shop;
    }

    private static String forwarded(String type, String outcome) {
        String original = Base64.getEncoder()
                .encodeToString(
                        ("{\"created_at\":" + Instant.now().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return """
                {"id":"1-1","verdict":"PASSED","provider":"razorpay","type":"%s","outcome":"%s",
                 "merchantRef":"SHOP-1","providerRef":"order_A","paymentRef":"pay_A","amount":49900,"currency":"INR",
                 "original":{"headers":{"x-razorpay-event-id":"evt_1"},"body":"%s"}}""".formatted(type, outcome, original);
    }

    private static String sign(String timestamp, String body) {
        return "v1=" + Hmac.sha256Hex(AEGIS_SECRET, timestamp + "." + body);
    }

    @Test
    void signedMessageReachesTheShop() {
        Shop shop = shop();
        String body = forwarded("WEBHOOK", "SUCCESS");
        String now = String.valueOf(Instant.now().getEpochSecond());

        assertThat(controller(shop).message(body, now, sign(now, body)).getBody())
                .isEqualTo("fulfilled SHOP-1");
    }

    @Test
    void unsignedOrStaleMessagesAreRefused() {
        Shop shop = shop();
        String body = forwarded("CALLBACK", "SUCCESS");
        String now = String.valueOf(Instant.now().getEpochSecond());
        String old = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());

        assertThat(controller(shop)
                        .message(body, now, "v1=forged")
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(controller(shop)
                        .message(body, old, sign(old, body))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(shop.order("SHOP-1").orElseThrow().fulfilments()).isZero();
    }

    @Test
    void returnPageChecksAegisSignature() {
        String signature = Hmac.sha256Hex(AEGIS_SECRET, "SHOP-1.PASSED");
        assertThat(controller(shop())
                        .returned("SHOP-1", "PASSED", signature)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(controller(shop())
                        .returned("SHOP-1", "PASSED", "forged")
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }
}
