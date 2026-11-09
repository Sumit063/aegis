package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static dev.aegis.provider.npci.NpciMessages.CURRENT;
import static dev.aegis.provider.npci.NpciMessages.billFetchRequest;
import static dev.aegis.provider.npci.NpciMessages.billFetchResponse;
import static dev.aegis.provider.npci.NpciMessages.billPaymentRequest;
import static dev.aegis.provider.npci.NpciMessages.billPaymentResponse;
import static dev.aegis.provider.npci.NpciMessages.signed;
import static dev.aegis.provider.npci.NpciMessages.txnStatusRequest;
import static dev.aegis.provider.npci.NpciMessages.txnStatusResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.FakeApplication;
import dev.aegis.FakeProvider;
import dev.aegis.FakeProvider.Answer;
import dev.aegis.IntegrationTest;
import dev.aegis.TestClock;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.TestKeys;
import dev.aegis.provider.spi.Xml;
import dev.aegis.provider.spi.XmlSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** BBPS at the COU end to end (F21): LLD §10 row by row, against signed switch messages. */
class BbpsIT extends IntegrationTest {

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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/gw/bbps/" + path))
                        .header("Content-Type", "application/xml")
                        .header("Authorization", "Bearer " + SHOP_API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(xml))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deliver(RawMessage message) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/bbps"))
                        .header("Content-Type", "application/xml")
                        .POST(HttpRequest.BodyPublishers.ofString(message.text()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private RawMessage result(String bill, String txn, String reason, String code, long amount) {
        return signed(billPaymentResponse(bill, txn, reason, code, amount, unique("M"), clock.instant()), CURRENT);
    }

    /** Bill fetch through Aegis: the switch answers ₹840 for the bill. */
    private String bill() throws Exception {
        String bill = unique("R");
        FakeProvider.on(
                "POST /bbps/BillFetchRequest",
                r -> new Answer(
                        200,
                        signed(billFetchResponse(bill, "000", 84_000), CURRENT).text()));
        HttpResponse<String> fetched = gateway("validations", billFetchRequest(bill));
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        return bill;
    }

    private List<JsonNode> delivered(String txn) {
        return FakeApplication.received().stream()
                .map(r -> json.readTree(r.body()))
                .filter(m -> txn.equals(m.path("paymentRef").asString(null)))
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
    void theFetchIsSignedByAegisAndFixesTheBill() throws Exception {
        String bill = bill();

        String sent = FakeProvider.received("/bbps/BillFetchRequest").getLast().body();
        assertThat(XmlSignature.verify(
                        Xml.parse(sent.getBytes(StandardCharsets.UTF_8)), List.of(TestKeys.AEGIS.getPublic())))
                .isTrue();
        assertThat(jdbc.queryForMap(
                        "select merchant_ref, amount, currency, status from validations where provider_ref = ?", bill))
                .containsEntry("merchant_ref", bill)
                .containsEntry("amount", 84_000L)
                .containsEntry("currency", "INR")
                .containsEntry("status", "CREATED");
    }

    /** LLD §10: pay, a retry refused, deemed, status too early, status later, the late result a duplicate. */
    @Test
    void billPaymentFromRequestToResult() throws Exception {
        String bill = bill();
        String first = unique("T");
        FakeProvider.on(
                "POST /bbps/BillPaymentRequest",
                r -> new Answer(
                        200,
                        "<bbps:Ack xmlns:bbps=\"http://bbps.org/schema\" api=\"BillPaymentRequest\" RspCd=\"Successful\"/>"));

        // 2. The payment goes out, signed, and is in flight.
        HttpResponse<String> paid = gateway("payments", billPaymentRequest(bill, first, 84_000));
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(paid.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        int sent = FakeProvider.received("/bbps/BillPaymentRequest").size();

        // 3. The user retries; the COU sends a second transaction: refused, never reaches the switch.
        HttpResponse<String> retry = gateway("payments", billPaymentRequest(bill, unique("T"), 84_000));
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(retry.headers().firstValue("X-Aegis-Verdict")).contains("BLOCKED DUPLICATE_PAYMENT");
        assertThat(FakeProvider.received("/bbps/BillPaymentRequest")).hasSize(sent);

        // 4. The switch reports a deemed transaction: Acked, forwarded.
        HttpResponse<String> deemed = deliver(result(bill, first, "Pending", "BFR011", 84_000));
        assertThat(deemed.statusCode()).isEqualTo(200);
        assertThat(Xml.parse(deemed.body().getBytes(StandardCharsets.UTF_8))
                        .getDocumentElement()
                        .getLocalName())
                .isEqualTo("Ack");
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(first).size() == 1);
        assertThat(delivered(first).getFirst().get("outcome").asString()).isEqualTo("PENDING");

        // 5. A status check 30 s later breaks NPCI's rule: refused, the switch is not called.
        FakeProvider.on(
                "POST /bbps/TxnStatusRequest",
                r -> new Answer(
                        200,
                        signed(txnStatusResponse(bill, first, "SUCCESS", 84_000), CURRENT)
                                .text()));
        int checks = FakeProvider.received("/bbps/TxnStatusRequest").size();
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> early = gateway("status", txnStatusRequest(bill, first));
        assertThat(early.statusCode()).isEqualTo(429);
        assertThat(FakeProvider.received("/bbps/TxnStatusRequest")).hasSize(checks);

        // 6. Two minutes after the request: allowed; SUCCESS passes, the bill is paid.
        clock.advance(Duration.ofSeconds(90));
        HttpResponse<String> later = gateway("status", txnStatusRequest(bill, first));
        assertThat(later.statusCode()).isEqualTo(200);
        assertThat(later.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        assertThat(jdbc.queryForObject("select status from validations where provider_ref = ?", String.class, bill))
                .isEqualTo("PAID");

        // 7. The switch's own SUCCESS arrives, twice: Acked, the same fact, never forwarded.
        RawMessage success = result(bill, first, "Successful", "000", 84_000);
        assertThat(deliver(success).statusCode()).isEqualTo(200);
        assertThat(deliver(success).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).until(() -> blocked(first, "DUPLICATE") == 1);
        assertThat(delivered(first)).hasSize(1);
    }

    @Test
    void aForgedResultIsRefusedAndALateFailureIsADowngrade() throws Exception {
        String bill = bill();
        String txn = unique("T");
        FakeProvider.on(
                "POST /bbps/BillPaymentRequest",
                r -> new Answer(200, "<bbps:Ack xmlns:bbps=\"http://bbps.org/schema\"/>"));
        gateway("payments", billPaymentRequest(bill, txn, 84_000));

        RawMessage forged = signed(
                billPaymentResponse(bill, txn, "Successful", "000", 84_000, unique("M"), clock.instant()), "attacker");
        assertThat(deliver(forged).statusCode()).isEqualTo(401);

        deliver(result(bill, txn, "Successful", "000", 84_000));
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(txn).size() == 1);
        deliver(result(bill, txn, "Failure", "E101", 84_000));

        await().atMost(Duration.ofSeconds(15)).until(() -> blocked(txn, "DOWNGRADE") == 1);
        assertThat(delivered(txn)).extracting(m -> m.get("outcome").asString()).containsExactly("SUCCESS");
    }

    @Test
    void aResultForAnotherAmountIsAMismatch() throws Exception {
        String bill = bill();
        String txn = unique("T");
        FakeProvider.on(
                "POST /bbps/BillPaymentRequest",
                r -> new Answer(200, "<bbps:Ack xmlns:bbps=\"http://bbps.org/schema\"/>"));
        gateway("payments", billPaymentRequest(bill, txn, 84_000));

        deliver(result(bill, txn, "Successful", "000", 100));

        await().atMost(Duration.ofSeconds(15)).until(() -> blocked(txn, "AMOUNT_MISMATCH") == 1);
        assertThat(delivered(txn)).isEmpty();
    }

    /** D33: a fetch reply the switch did not sign is BAD_SIGNATURE and the bill cannot be paid. */
    @Test
    void anUnsignedFetchReplyIsBadSignatureAndTheBillIsNotPayable() throws Exception {
        String bill = unique("R");
        FakeProvider.on("POST /bbps/BillFetchRequest", r -> new Answer(200, billFetchResponse(bill, "000", 1)));

        HttpResponse<String> fetched = gateway("validations", billFetchRequest(bill));

        assertThat(fetched.headers().firstValue("X-Aegis-Verdict")).contains("BLOCKED BAD_SIGNATURE");
        assertThat(jdbc.queryForObject("select status from validations where merchant_ref = ?", String.class, bill))
                .isEqualTo("FAILED");
        assertThat(gateway("payments", billPaymentRequest(bill, unique("T"), 1)).statusCode())
                .isEqualTo(409);
    }

    @Test
    void aRequestTheApplicationSignedOrOfTheWrongKindNeverLeaves() throws Exception {
        int before = FakeProvider.received("/bbps/").size();

        assertThat(gateway("payments", billFetchRequest(unique("R"))).statusCode())
                .isEqualTo(400);
        assertThat(gateway(
                                "validations",
                                signed(billFetchRequest(unique("R")), CURRENT).text())
                        .statusCode())
                .isEqualTo(400);
        assertThat(FakeProvider.received("/bbps/")).hasSize(before);
    }
}
