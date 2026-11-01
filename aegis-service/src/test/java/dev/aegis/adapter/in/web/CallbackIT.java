package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Validation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/** The browser callback (LLD §3): checked inline, stored, and the browser redirected with a signed result. */
class CallbackIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ValidationRepository validations;

    @Autowired
    PaymentRepository payments;

    @Autowired
    TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", "session=secret")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String expectedLocation(String ref, String verdict) {
        return "http://shop.test/return?ref=" + ref + "&verdict=" + verdict + "&signature="
                + Scenario.sign(SHOP_SECRET, ref + "." + verdict);
    }

    @Test
    void aGenuineCallbackPassesAndTheBrowserGoesBackWithASignedResult() throws Exception {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Validation order = Scenario.order(validations, tx, orderId, 49_900);

        HttpResponse<String> response =
                post("/in/razorpay/callback", Scenario.callback(orderId, paymentId, RAZORPAY_KEY_SECRET));

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).contains(expectedLocation("SHOP-" + orderId, "PASSED"));
        assertThat(validations.find(order.id()).orElseThrow().status()).isEqualTo(Validation.Status.PAID);
        var entry = payments.ofValidation(order.id()).getFirst().entries().getFirst();
        assertThat(entry.type()).isEqualTo(MessageType.CALLBACK);
        assertThat(entry.verdict()).isEqualTo(Verdict.PASSED);

        // The browser posts it again (back button): same message, same answer, nothing new stored.
        assertThat(post("/in/razorpay/callback", Scenario.callback(orderId, paymentId, RAZORPAY_KEY_SECRET))
                        .headers()
                        .firstValue("Location"))
                .contains(expectedLocation("SHOP-" + orderId, "PASSED"));
        assertThat(payments.ofValidation(order.id())
                        .getFirst()
                        .entries()
                        .getFirst()
                        .duplicates())
                .isEqualTo(1);
    }

    /** F7 and F8: an unsigned "success", or a signature for another order, is blocked. */
    @Test
    void forgedCallbacksAreBlocked() throws Exception {
        String orderId = unique("order_");
        Scenario.order(validations, tx, orderId, 1_000_000);

        HttpResponse<String> unsigned = post(
                "/in/razorpay/callback", "razorpay_payment_id=pay_x&razorpay_order_id=" + orderId + "&status=success");
        String smallSigned = Scenario.callback("order_small", "pay_y", RAZORPAY_KEY_SECRET);
        HttpResponse<String> swapped = post("/in/razorpay/callback", smallSigned.replace("order_small", orderId));

        assertThat(unsigned.headers().firstValue("Location")).contains(expectedLocation("unknown", "BLOCKED"));
        assertThat(swapped.headers().firstValue("Location")).contains(expectedLocation("unknown", "BLOCKED"));
    }

    @Test
    void unknownConnection() throws Exception {
        assertThat(post("/in/nope/callback", "a=b").statusCode()).isEqualTo(404);
    }
}
