package dev.aegis.lab.npci;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NpciSwitchTest {

    static final KeyPair SWITCH = XmlDsig.generate();
    static final KeyPair AEGIS = XmlDsig.generate();
    static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    /** Aegis answers 503 to this many deliveries before acknowledging (an Aegis that is down). */
    static final AtomicInteger FAILURES = new AtomicInteger();

    static final HttpServer AEGIS_INGRESS = ingress();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        String callback = "http://127.0.0.1:" + AEGIS_INGRESS.getAddress().getPort();
        registry.add(
                "npci.switch-key",
                () -> Base64.getEncoder().encodeToString(SWITCH.getPrivate().getEncoded()));
        registry.add(
                "npci.aegis-key",
                () -> Base64.getEncoder().encodeToString(AEGIS.getPublic().getEncoded()));
        registry.add("npci.bbps-callback", () -> callback + "/in/bbps");
        registry.add("npci.upi-callback", () -> callback + "/in/upi");
        registry.add("npci.delay", () -> "20ms");
        registry.add("npci.redeliver", () -> "10s");
    }

    @LocalServerPort
    int port;

    @Autowired
    Switch npci;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        RECEIVED.clear();
        FAILURES.set(0);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", path.startsWith("/test") ? "application/json" : "application/xml")
                        .method(path.equals("/test/next") ? "PUT" : "POST", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signedByAegis(String path, String xml) throws Exception {
        return post(path, XmlDsig.sign(xml, AEGIS.getPrivate()));
    }

    private static String next() throws InterruptedException {
        String message = RECEIVED.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("a delivery").isNotNull();
        return message;
    }

    private static String id() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String billPayment(String txn) {
        return """
                <bbps:BillPaymentRequest xmlns:bbps="http://bbps.org/schema"><Head ver="1.0" ts="2026-11-10T10:00:00+05:30" refId="R1" msgId="P%s"/>\
                <Txn txnReferenceId="%s"/><Amount><Amt amount="84000" currency="356"/></Amount></bbps:BillPaymentRequest>""".formatted(txn, txn);
    }

    private static String reqPay(String txn) {
        return """
                <upi:ReqPay xmlns:upi="http://npci.org/upi/schema/"><Head ver="2.0" ts="2026-11-10T10:00:00+05:30" msgId="P%s"/>\
                <Txn id="%s" refId="V1" type="PAY"/><Payer addr="c@psp"><Amount value="499.00" curr="INR"/></Payer>\
                <Payee addr="shop@bank"/></upi:ReqPay>""".formatted(txn, txn);
    }

    @Test
    void aRequestNotSignedByAegisIsRefused() throws Exception {
        int before = npci.received().getOrDefault("rejected", 0);

        assertThat(post("/bbps/BillFetchRequest", billPayment(id())).statusCode())
                .isEqualTo(401);
        assertThat(post("/bbps/BillFetchRequest", XmlDsig.sign(billPayment(id()), SWITCH.getPrivate()))
                        .statusCode())
                .isEqualTo(401);
        assertThat(npci.received().get("rejected")).isEqualTo(before + 2);
    }

    @Test
    void aBillFetchIsAnsweredAndSignedByTheSwitch() throws Exception {
        HttpResponse<String> fetched = signedByAegis(
                "/bbps/BillFetchRequest",
                "<bbps:BillFetchRequest xmlns:bbps=\"http://bbps.org/schema\"><Head refId=\"R9\" msgId=\"F9\"/></bbps:BillFetchRequest>");

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(XmlDsig.verify(fetched.body(), SWITCH.getPublic())).isTrue();
        assertThat(fetched.body()).contains("refId=\"R9\"", "amount=\"84000\"", "responseCode=\"000\"");
    }

    @Test
    void aPaymentIsAcknowledgedThenItsResultDeliveredSigned() throws Exception {
        String txn = id();

        HttpResponse<String> ack = signedByAegis("/bbps/BillPaymentRequest", billPayment(txn));

        assertThat(ack.body()).contains("<bbps:Ack", "api=\"BillPaymentRequest\"");
        String result = next();
        assertThat(XmlDsig.verify(result, SWITCH.getPublic())).isTrue();
        assertThat(result)
                .contains("BillPaymentResponse", "txnReferenceId=\"" + txn + "\"", "responseReason=\"Successful\"");
    }

    @Test
    void aDeemedUpiPaymentIsReversed() throws Exception {
        String txn = id();
        post("/test/next", "{\"script\":\"DEEMED_THEN_REVERSAL\",\"duplicates\":0,\"wrongAmount\":false}");

        signedByAegis("/upi/ReqPay", reqPay(txn));

        assertThat(next()).contains("type=\"PAY\"", "result=\"DEEMED\"");
        assertThat(next())
                .contains(
                        "type=\"REVERSAL\"", "orgTxnId=\"" + txn + "\"", "result=\"SUCCESS\"", "settAmount=\"499.00\"");
        assertThat(npci.txn(txn).orElseThrow().status).isEqualTo("REVERSED");
    }

    @Test
    void anUnacknowledgedResultIsRedeliveredUnchangedUntilAcknowledged() throws Exception {
        String txn = id();
        FAILURES.set(2);

        signedByAegis("/bbps/BillPaymentRequest", billPayment(txn));

        List<String> copies = List.of(next(), next(), next());
        assertThat(copies).allMatch(copies.getFirst()::equals);
        assertThat(RECEIVED.poll(1500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(npci.txn(txn).orElseThrow().deliveries.get()).isEqualTo(3);
    }

    @Test
    void duplicatesAreIdenticalCopies() throws Exception {
        post("/test/next", "{\"script\":\"SUCCESS\",\"duplicates\":2,\"wrongAmount\":false}");

        signedByAegis("/bbps/BillPaymentRequest", billPayment(id()));

        List<String> copies = List.of(next(), next(), next());
        assertThat(copies).allMatch(copies.getFirst()::equals);
    }

    @Test
    void aStatusCheckReportsTheTransaction() throws Exception {
        String txn = id();
        post("/test/next", "{\"script\":\"SILENT\",\"duplicates\":0,\"wrongAmount\":false}");
        signedByAegis("/bbps/BillPaymentRequest", billPayment(txn));

        HttpResponse<String> status = signedByAegis(
                "/bbps/TxnStatusRequest",
                "<bbps:TxnStatusRequest xmlns:bbps=\"http://bbps.org/schema\"><Head refId=\"R1\" msgId=\"S1\"/>"
                        + "<TxnStatusReq txnReferenceId=\"" + txn + "\"/></bbps:TxnStatusRequest>");

        assertThat(XmlDsig.verify(status.body(), SWITCH.getPublic())).isTrue();
        assertThat(status.body()).contains("status=\"PENDING\"");
    }

    /**
     * Attack messages fail verification (the SHA-1 one is signed by the switch's key with a refused
     * algorithm); stale and replayed ones are genuinely signed: Aegis's freshness and identity catch them.
     */
    @ParameterizedTest
    @CsvSource({
        "forged, false", "tampered, false", "wrapped, false", "unsigned, false", "xxe, false",
        "sha1, false", "hmac, false", "keyinfo, false", "stale, true", "replay, true"
    })
    void attackMessagesAreWhatTheyClaimToBe(String kind, boolean genuinelySigned) throws Exception {
        String txn = id();
        signedByAegis("/upi/ReqPay", reqPay(txn));
        next();

        assertThat(post("/test/attack?kind=" + kind + "&txn=" + txn, "").statusCode())
                .isEqualTo(200);
        String attack = next();

        assertThat(XmlDsig.verify(attack, SWITCH.getPublic())).isEqualTo(genuinelySigned);
    }

    private static HttpServer ingress() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int status = FAILURES.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 503 : 200;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
