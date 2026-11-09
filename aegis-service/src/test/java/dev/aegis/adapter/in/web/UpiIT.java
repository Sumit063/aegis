package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static dev.aegis.provider.npci.NpciMessages.CURRENT;
import static dev.aegis.provider.npci.NpciMessages.reqChkTxn;
import static dev.aegis.provider.npci.NpciMessages.reqPay;
import static dev.aegis.provider.npci.NpciMessages.reqValAdd;
import static dev.aegis.provider.npci.NpciMessages.respPay;
import static dev.aegis.provider.npci.NpciMessages.respValAdd;
import static dev.aegis.provider.npci.NpciMessages.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.FakeApplication;
import dev.aegis.FakeProvider;
import dev.aegis.FakeProvider.Answer;
import dev.aegis.IntegrationTest;
import dev.aegis.TestClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** UPI at the payer's PSP end to end (F22): validate, pay once, deemed, reversal, retry. */
class UpiIT extends IntegrationTest {

    private static final String ACK = "<upi:Ack xmlns:upi=\"http://npci.org/upi/schema/\" api=\"ReqPay\"/>";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    TestClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> gateway(String path, String xml) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/gw/upi/" + path))
                        .header("Content-Type", "application/xml")
                        .header("Authorization", "Bearer " + SHOP_API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(xml))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int deliver(String xml) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/upi"))
                                .header("Content-Type", "application/xml")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        signed(xml, CURRENT).text()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    /** The payee is confirmed for ₹499.00, the amount the payer instructs (D34). */
    private String validation() throws Exception {
        String validation = unique("V");
        FakeProvider.on(
                "POST /upi/ReqValAdd",
                r -> new Answer(
                        200, signed(respValAdd(validation, "SUCCESS"), CURRENT).text()));
        FakeProvider.on("POST /upi/ReqPay", r -> new Answer(200, ACK));
        HttpResponse<String> validated = gateway("validations", reqValAdd(validation, unique("ORDER-"), "499.00"));
        assertThat(validated.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        return validation;
    }

    private List<String> outcomes(String txn) {
        return FakeApplication.received().stream()
                .map(r -> json.readTree(r.body()))
                .filter(m -> txn.equals(m.path("paymentRef").asString(null)))
                .map(m -> m.get("outcome").asString())
                .toList();
    }

    private int blocked(String txn, String reason) {
        return jdbc.queryForObject(
                "select count(*) from payments p, jsonb_array_elements(p.messages) e"
                        + " where p.payment_ref = ? and e->>'reason' = ?",
                Integer.class,
                txn,
                reason);
    }

    @Test
    void theValidationRecordsThePayersAmount() throws Exception {
        String validation = validation();

        assertThat(jdbc.queryForMap(
                        "select amount, currency, status from validations where provider_ref = ?", validation))
                .containsEntry("amount", 49_900L)
                .containsEntry("currency", "INR")
                .containsEntry("status", "CREATED");
    }

    /** Deemed, then reversed: the customer's money came back, so a new payment may go out. */
    @Test
    void aDeemedPaymentThatIsReversedCanBePaidAgain() throws Exception {
        String validation = validation();
        String first = unique("T");

        assertThat(gateway("payments", reqPay(validation, first, "499.00")).statusCode())
                .isEqualTo(200);
        assertThat(gateway("payments", reqPay(validation, unique("T"), "499.00"))
                        .statusCode())
                .isEqualTo(409);

        assertThat(deliver(respPay(validation, first, "PAY", null, "DEEMED", "499.00", unique("M"), clock.instant())))
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).until(() -> outcomes(first).size() == 1);
        assertThat(deliver(respPay(
                        validation,
                        unique("RV"),
                        "REVERSAL",
                        first,
                        "SUCCESS",
                        "499.00",
                        unique("M"),
                        clock.instant())))
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).until(() -> outcomes(first).size() == 2);
        assertThat(outcomes(first)).containsExactly("PENDING", "REVERSED");

        assertThat(gateway("payments", reqPay(validation, unique("T"), "499.00"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select status from validations where provider_ref = ?", String.class, validation))
                .isEqualTo("CREATED");
    }

    @Test
    void aSettledAmountOtherThanInstructedIsAMismatch() throws Exception {
        String validation = validation();
        String txn = unique("T");
        gateway("payments", reqPay(validation, txn, "499.00"));

        deliver(respPay(validation, txn, "PAY", null, "SUCCESS", "4.99", unique("M"), clock.instant()));

        await().atMost(Duration.ofSeconds(15)).until(() -> blocked(txn, "AMOUNT_MISMATCH") == 1);
        assertThat(outcomes(txn)).isEmpty();
    }

    /** A payment request for another amount than the validated one never leaves. */
    @Test
    void aPaymentRequestForAnotherAmountNeverLeaves() throws Exception {
        String validation = validation();
        int sent = FakeProvider.received("/upi/ReqPay").size();

        HttpResponse<String> response = gateway("payments", reqPay(validation, unique("T"), "4990.00"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("X-Aegis-Verdict")).contains("BLOCKED AMOUNT_MISMATCH");
        assertThat(FakeProvider.received("/upi/ReqPay")).hasSize(sent);
    }

    @Test
    void anEarlyStatusCheckNeverReachesTheSwitch() throws Exception {
        String validation = validation();
        String txn = unique("T");
        gateway("payments", reqPay(validation, txn, "499.00"));
        int checks = FakeProvider.received("/upi/ReqChkTxn").size();

        HttpResponse<String> early = gateway("status", reqChkTxn(validation, txn));

        assertThat(early.statusCode()).isEqualTo(429);
        assertThat(FakeProvider.received("/upi/ReqChkTxn")).hasSize(checks);
    }
}
