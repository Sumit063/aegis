package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.FakeApplication;
import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import dev.aegis.application.port.in.RetentionUseCase;
import dev.aegis.application.port.out.EvidenceCipherPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LLD §14 end to end: partners call the shop's partner API through Aegis (T20–T28). Signatures are
 * computed here from the documented canonical string, independently of Aegis's code.
 */
class PartnerIT extends IntegrationTest {

    static final String BODY = "{\"mobile\":\"9000000001\",\"amount\":49900}";

    @LocalServerPort
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RetentionUseCase retention;

    @Autowired
    EvidenceCipherPort cipher;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void answerNormally() {
        FakeApplication.partnerAnswer(201, 0);
    }

    record Signed(String partner, String secret, String method, String path, String requestId, String body, long ts) {

        Signed(String method, String path, String requestId, String body) {
            this(
                    "acme",
                    ACME_SECRET,
                    method,
                    path,
                    requestId,
                    body,
                    Instant.now().getEpochSecond());
        }

        String signature() {
            String canonical =
                    String.join("\n", "v1", method, "/partner" + path, String.valueOf(ts), requestId, sha256(body));
            return "v1=" + Scenario.sign(secret, canonical);
        }

        Signed body(String other) {
            return new Signed(partner, secret, method, path, requestId, other, ts);
        }

        Signed at(long epochSecond) {
            return new Signed(partner, secret, method, path, requestId, body, epochSecond);
        }

        Signed by(String otherPartner, String otherSecret) {
            return new Signed(otherPartner, otherSecret, method, path, requestId, body, ts);
        }
    }

    static Signed recharge(String requestId) {
        return new Signed("POST", "/recharges", requestId, BODY);
    }

    HttpResponse<String> send(Signed s) throws Exception {
        return send(s, s.signature());
    }

    HttpResponse<String> send(Signed s, String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/partner" + s.path()))
                .header("X-Partner-Id", s.partner())
                .header("X-Request-Id", s.requestId())
                .header("X-Timestamp", String.valueOf(s.ts()))
                .header("X-Signature", signature)
                .header("Content-Type", "application/json");
        request.method(
                s.method(),
                s.body().isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(s.body()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String sha256(String body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<FakeApplication.PartnerCall> callsFor(String requestId) {
        return FakeApplication.partnerCalls().stream()
                .filter(c -> requestId.equals(c.requestId()))
                .toList();
    }

    @Test
    @DisplayName(
            "T20 a signed request reaches the application once, signed by Aegis, without the partner's credentials")
    void aSignedRequestIsAnsweredByTheApplication() throws Exception {
        String id = unique("r-");

        HttpResponse<String> response = send(new Signed("POST", "/recharges?channel=app", id, BODY));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"executed\":\"" + id + "\",\"n\":1}");
        assertThat(response.headers().firstValue("X-Aegis-Request-Id")).contains(id);
        assertThat(response.headers().firstValue("X-Aegis-Replayed")).isEmpty();
        FakeApplication.PartnerCall call = callsFor(id).getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/recharges?channel=app");
        assertThat(call.partner()).isEqualTo("acme");
        assertThat(call.contentType()).isEqualTo("application/json");
        assertThat(new String(call.body(), StandardCharsets.UTF_8)).isEqualTo(BODY);
        assertThat(call.partnerCredentialsPassedOn()).isFalse();
        String signed = String.join(".", call.timestamp(), "acme", id, "POST", "/recharges?channel=app") + "." + BODY;
        assertThat(call.signature()).isEqualTo("v1=" + Scenario.sign(SHOP_SECRET, signed));
    }

    @Test
    @DisplayName("T21 forged, tampered, stale, unknown or unsigned requests are 401 and never reach the application")
    void unauthenticatedRequestsNeverReachTheApplication() throws Exception {
        String id = unique("r-");
        Signed good = recharge(id);
        long now = Instant.now().getEpochSecond();

        assertThat(send(good, "v1=" + "0".repeat(64)).statusCode()).as("forged").isEqualTo(401);
        assertThat(send(good.body("{\"amount\":1}"), good.signature()).statusCode())
                .as("body changed after signing")
                .isEqualTo(401);
        assertThat(send(good.by("acme", TINY_SECRET)).statusCode())
                .as("another partner's secret")
                .isEqualTo(401);
        assertThat(send(good.by("nobody", ACME_SECRET)).statusCode())
                .as("unknown partner")
                .isEqualTo(401);
        assertThat(send(good.at(now - 600)).statusCode()).as("stale").isEqualTo(401);
        assertThat(send(good.at(now + 600)).statusCode()).as("from the future").isEqualTo(401);
        assertThat(send(good, "").statusCode()).as("unsigned").isEqualTo(401);

        assertThat(callsFor(id)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from partner_requests where request_id = ?", Integer.class, id))
                .as("nothing is stored before the partner is authenticated")
                .isZero();
    }

    @Test
    @DisplayName("T22 an operation, method or path not granted is 403")
    void onlyGrantedOperationsAndMethodsPass() throws Exception {
        String id = unique("r-");

        assertThat(send(new Signed("POST", "/payouts", id, BODY)).statusCode()).isEqualTo(403);
        assertThat(send(new Signed("DELETE", "/recharges", id, "")).statusCode())
                .isEqualTo(403);
        assertThat(send(new Signed("POST", "/recharges/..%2F..%2Fadmin", id, BODY))
                        .statusCode())
                .as("refused by the server (400) or by Aegis (403), never forwarded")
                .isIn(400, 403);
        assertThat(send(new Signed("POST", "/recharges/../status", id, BODY)).statusCode())
                .isIn(400, 403);
        assertThat(send(new Signed("GET", "/status/" + id, unique("s-"), "")).statusCode())
                .as("a granted read")
                .isEqualTo(201);
        assertThat(callsFor(id)).isEmpty();
    }

    @Test
    @DisplayName("T23 a retry with the same request ID gets the first answer and is not executed again")
    void aRetryIsReplayedNotExecuted() throws Exception {
        String id = unique("r-");
        HttpResponse<String> first = send(recharge(id));
        // A retry is signed again, later: same request, new signature.
        HttpResponse<String> retry = send(recharge(id).at(Instant.now().getEpochSecond() + 2));

        assertThat(retry.statusCode()).isEqualTo(first.statusCode());
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("X-Aegis-Replayed")).contains("true");
        assertThat(FakeApplication.executions(id)).isOne();
    }

    @Test
    @DisplayName("T24 twenty concurrent copies of one request execute once")
    void concurrentCopiesExecuteOnce() throws Exception {
        String id = unique("r-");
        FakeApplication.partnerAnswer(201, 300);
        List<Callable<Integer>> copies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            copies.add(() -> send(recharge(id)).statusCode());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> status : pool.invokeAll(copies)) {
                statuses.add(status.get());
            }
        }

        assertThat(FakeApplication.executions(id)).isOne();
        assertThat(statuses).containsOnly(201, 409).contains(201);
        assertThat(send(recharge(id)).headers().firstValue("X-Aegis-Replayed")).contains("true");
    }

    @Test
    @DisplayName("T25 a request ID reused for a different request is 422")
    void aReusedRequestIdIsRefused() throws Exception {
        String id = unique("r-");
        send(recharge(id));

        HttpResponse<String> reused = send(recharge(id).body("{\"mobile\":\"9000000002\",\"amount\":99900}"));

        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(reused.body()).contains("REQUEST_ID_REUSED");
        assertThat(FakeApplication.executions(id)).isOne();
    }

    @Test
    @DisplayName("T26 a partner over its operation's rate gets 429 with Retry-After; other partners are unaffected")
    void ratesArePerPartnerAndOperation() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            responses.add(send(recharge(unique("t-")).by("tiny", TINY_SECRET)));
        }

        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSizeLessThanOrEqualTo(4);
        HttpResponse<String> limited = responses.getLast();
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(send(new Signed("GET", "/status/x", unique("s-"), "").by("tiny", TINY_SECRET))
                        .statusCode())
                .as("the partner's other operation has its own allowance")
                .isEqualTo(201);
        assertThat(send(recharge(unique("r-"))).statusCode())
                .as("another partner")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("T27 the previous secret still verifies during rotation")
    void thePreviousSecretVerifies() throws Exception {
        assertThat(send(recharge(unique("r-")).by("acme", ACME_PREVIOUS_SECRET)).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("T28 an error answer is an answer: stored and replayed, never executed twice")
    void anErrorAnswerIsReplayedToo() throws Exception {
        String id = unique("r-");
        FakeApplication.partnerAnswer(503, 0);
        HttpResponse<String> first = send(recharge(id));
        FakeApplication.partnerAnswer(201, 0);
        HttpResponse<String> retry = send(recharge(id));

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(retry.statusCode()).isEqualTo(503);
        assertThat(retry.headers().firstValue("X-Aegis-Replayed")).contains("true");
        assertThat(FakeApplication.executions(id)).isOne();
    }

    @Test
    void operatorsSeeTheStateNeverTheBodies() throws Exception {
        String id = unique("r-");
        send(recharge(id));

        send(new Signed("GET", "/status/" + id + "?mobile=9000000001", unique("s-"), ""));
        HttpResponse<String> view = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/partners/acme/requests/" + id))
                        .header("Authorization", "Bearer " + ADMIN_KEY)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(view.body())
                .contains("\"state\":\"ANSWERED\"", "\"httpStatus\":201", "\"path\":\"/recharges\"")
                .doesNotContain("9000000001", "executed");
        assertThat(jdbc.queryForList(
                        "select path from partner_requests where path like ?", String.class, "/status/" + id + "%"))
                .as("a query is never kept in clear")
                .containsExactly("/status/" + id);
        assertThat(jdbc.queryForObject(
                        "select request || response from partner_requests where request_id = ?", String.class, id))
                .as("bodies are sealed at rest")
                .doesNotContain("9000000001")
                .startsWith("enc:v1:");
    }

    @Test
    void metricsCountAnsweredReplayedAndRefusedRequests() throws Exception {
        String id = unique("r-");
        send(recharge(id));
        send(recharge(id));
        send(recharge(unique("r-")), "v1=forged");

        String metrics = http.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();

        assertThat(metrics)
                .containsPattern(
                        "aegis_partner_requests_total\\{.*operation=\"recharges\".*partner=\"acme\".*result=\"ANSWERED\".*\\}")
                .containsPattern("aegis_partner_requests_total\\{.*result=\"REPLAYED\".*\\}")
                .containsPattern(
                        "aegis_partner_requests_total\\{.*operation=\"other\".*result=\"UNAUTHENTICATED\".*\\}")
                .contains("aegis_partner_upstream_seconds_bucket");
    }

    @Test
    void requestIdsArePurgedAfterRetentionOnly() {
        String recent = unique("r-");
        String old = unique("r-");
        String insert = "insert into partner_requests (partner, request_id, request_hash, method, path, state, request,"
                + " created_at, updated_at) values ('acme', ?, 'h', 'POST', '/recharges', 'ANSWERED', ?,"
                + " now() - ?::interval, now() - ?::interval)";
        String sealed = cipher.seal(BODY.getBytes(StandardCharsets.UTF_8));
        jdbc.update(insert, recent, sealed, "29 days", "29 days");
        jdbc.update(insert, old, sealed, "31 days", "31 days");

        retention.purge();

        assertThat(jdbc.queryForObject(
                        "select count(*) from partner_requests where request_id = ?", Integer.class, old))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from partner_requests where request_id = ?", Integer.class, recent))
                .isOne();
    }
}
