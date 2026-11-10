package dev.aegis.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.application.port.in.PartnerUseCase.Answer;
import dev.aegis.application.port.in.PartnerUseCase.Call;
import dev.aegis.application.port.in.UnavailableException;
import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.Partner;
import dev.aegis.application.port.out.PartnerClientPort;
import dev.aegis.application.port.out.PartnerRequestRepository;
import dev.aegis.application.port.out.RateLimiterPort;
import dev.aegis.application.port.out.RateLimiterPort.Decision;
import dev.aegis.application.port.out.RateLimiterPort.Rate;
import dev.aegis.domain.partner.PartnerRequest;
import dev.aegis.domain.partner.Stored;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.Secret;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The failure paths of {@link PartnerService}; the happy paths run end to end in PartnerIT. */
class PartnerServiceTest {

    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    static final Secret SECRET = new Secret("partner-secret-for-unit-tests-000000001");
    static final byte[] BODY = "{\"amount\":49900}".getBytes(StandardCharsets.UTF_8);

    final Rows rows = new Rows();
    final List<String> limiterKeys = new ArrayList<>();
    Function<String, Decision> limits = key -> Decision.ALLOWED;
    Function<PartnerClientPort.Request, PartnerClientPort.Response> application =
            request -> new PartnerClientPort.Response(201, "application/json", "{\"id\":\"r-1\"}".getBytes());
    int calls;

    final RateLimiterPort limiter = (key, rate) -> {
        limiterKeys.add(key);
        return limits.apply(key);
    };
    final PartnerClientPort client = (app, request) -> {
        calls++;
        return application.apply(request);
    };
    final PartnerService service = new PartnerService(
            directory(),
            limiter,
            rows,
            client,
            Fakes.CIPHER,
            Fakes.METRICS,
            PartnerSettings.DEFAULTS,
            Clock.fixed(NOW, ZoneOffset.UTC));

    static DirectoryPort directory() {
        Partner acme = new Partner(
                "acme",
                "shop",
                List.of(SECRET),
                null,
                new Rate(600, 50),
                Map.of(
                        "recharges", new Partner.Grant(Set.of("POST"), new Rate(60, 5)),
                        "status", new Partner.Grant(Set.of("GET"), null)));
        Partner certified =
                new Partner("bank", "shop", List.of(SECRET), "CN=bank", new Rate(600, 50), acme.operations());
        Application shop = new Application(
                "shop",
                new Secret("shop-api-key-for-unit-tests-00000000001"),
                URI.create("http://shop/aegis"),
                null,
                null,
                new Secret("shop-signing-secret-unit-tests-0000001"),
                null,
                URI.create("http://shop/partner-api"));
        DirectoryPort base = Fakes.directory(List.of(), List.of(shop));
        return new DirectoryPort() {
            @Override
            public Optional<Connection> connection(String name) {
                return base.connection(name);
            }

            @Override
            public Optional<Application> application(String name) {
                return base.application(name);
            }

            @Override
            public Optional<Application> applicationByKey(String apiKey) {
                return base.applicationByKey(apiKey);
            }

            @Override
            public Collection<Connection> connections() {
                return base.connections();
            }

            @Override
            public Optional<Partner> partner(String name) {
                return Optional.ofNullable(
                        Map.of("acme", acme, "bank", certified).get(name));
            }
        };
    }

    static Call call(String partner, String method, String path, String requestId, String subject) {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String signature = PartnerSignature.sign(
                SECRET, PartnerSignature.canonical(method, "/partner" + path, timestamp, requestId, BODY));
        return new Call(partner, requestId, timestamp, signature, subject, method, path, "application/json", BODY);
    }

    static Call recharge(String requestId) {
        return call("acme", "POST", "/recharges", requestId, null);
    }

    @Test
    void theApplicationsAnswerIsRecordedAndReplayedForTheSameRequestId() {
        Answer first = service.call(recharge("r-1"));
        Answer again = service.call(recharge("r-1"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.replayed()).isFalse();
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(calls).isEqualTo(1);
        assertThat(rows.rows.get("acme/r-1").sealedAnswer()).startsWith("sealed:");
    }

    @Test
    void thePartnersRateIsTakenFirstThenTheOperationsOwnRate() {
        service.call(recharge("r-1"));
        service.call(call("acme", "GET", "/status/r-1", "s-1", null));

        assertThat(limiterKeys).containsExactly("partner:acme", "partner:acme:recharges", "partner:acme");
    }

    @Test
    void aRefusedPartnerRateCostsNoOperationAllowanceAndSaysWhenToRetry() {
        limits = key -> new Decision(false, Duration.ofMillis(1500));

        Answer answer = service.call(recharge("r-1"));

        assertThat(answer.status()).isEqualTo(429);
        assertThat(answer.refusal()).isEqualTo("RATE_LIMIT");
        assertThat(answer.retryAfter()).isEqualTo(Duration.ofMillis(1500));
        assertThat(limiterKeys).containsExactly("partner:acme");
        assertThat(calls).isZero();
        assertThat(rows.rows).isEmpty();
    }

    @Test
    void anUnreachableRateStoreRefusesRatherThanLetsThrough() {
        limits = key -> {
            throw new IllegalStateException("redis down");
        };

        Answer answer = service.call(recharge("r-1"));

        assertThat(answer.status()).isEqualTo(503);
        assertThat(answer.refusal()).isEqualTo("LIMITS_UNAVAILABLE");
        assertThat(calls).isZero();
    }

    @Test
    void anApplicationNeverReachedFreesTheRequestIdForTheRetry() {
        application = request -> {
            throw new PartnerClientPort.NotSentException("refused", null);
        };
        Answer down = service.call(recharge("r-1"));
        application = request -> new PartnerClientPort.Response(201, "application/json", "{}".getBytes());
        Answer retried = service.call(recharge("r-1"));

        assertThat(down.status()).isEqualTo(503);
        assertThat(down.refusal()).isEqualTo("APPLICATION_UNAVAILABLE");
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.replayed()).isFalse();
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void noAnswerMakesTheOutcomeUnknownForGoodAndIsNeverExecutedAgain() {
        application = request -> {
            throw new PartnerClientPort.NoAnswerException("timeout", null);
        };
        Answer first = service.call(recharge("r-1"));
        Answer again = service.call(recharge("r-1"));

        assertThat(first.status()).isEqualTo(504);
        assertThat(again.status()).isEqualTo(504);
        assertThat(again.refusal()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(calls).isEqualTo(1);
    }

    @Test
    void anAnswerThatCannotBeRecordedStillReachesThePartner() {
        rows.failWrites = true;

        Answer answer = service.call(recharge("r-1"));

        assertThat(answer.status()).isEqualTo(201);
        assertThat(rows.rows.get("acme/r-1").stored().state()).isEqualTo(Stored.State.IN_PROGRESS);
    }

    @Test
    void aDatabaseThatCannotClaimStopsTheRequestBeforeTheApplication() {
        rows.failClaims = true;

        assertThatThrownBy(() -> service.call(recharge("r-1"))).isInstanceOf(UnavailableException.class);
        assertThat(calls).isZero();
    }

    @Test
    void aPartnerBoundToACertificateNeedsIt() {
        assertThat(service.call(call("bank", "POST", "/recharges", "r-1", "CN=bank"))
                        .status())
                .isEqualTo(201);
        assertThat(service.call(call("bank", "POST", "/recharges", "r-2", null)).refusal())
                .isEqualTo("WRONG_CERTIFICATE");
        assertThat(service.call(call("bank", "POST", "/recharges", "r-3", "CN=other"))
                        .refusal())
                .isEqualTo("WRONG_CERTIFICATE");
    }

    @Test
    void malformedHeadersAreUnauthenticatedWithoutTouchingStorage() {
        Call good = recharge("r-1");
        List<Call> malformed = List.of(
                new Call(null, "r-1", good.timestamp(), good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("nobody", "r-1", good.timestamp(), good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("acme", null, good.timestamp(), good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("acme", "bad id", good.timestamp(), good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("acme", "r-1", null, good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("acme", "r-1", "-5", good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("acme", "r-1", "1e9", good.signature(), null, "POST", "/recharges", null, BODY),
                new Call("acme", "r-1", good.timestamp(), null, null, "POST", "/recharges", null, BODY));

        assertThat(malformed)
                .allSatisfy(c -> assertThat(service.call(c).status()).isEqualTo(401));
        assertThat(rows.rows).isEmpty();
        assertThat(limiterKeys).isEmpty();
    }

    /** {@code partner_requests} in memory, with the same claim semantics. */
    static final class Rows implements PartnerRequestRepository {

        final Map<String, Row> rows = new ConcurrentHashMap<>();
        boolean failWrites;
        boolean failClaims;

        @Override
        public Optional<Row> claim(PartnerRequest request, String sealedBody, Instant now) {
            if (failClaims) {
                throw new IllegalStateException("database down");
            }
            Row row = new Row(
                    request.partner(),
                    request.requestId(),
                    request.method(),
                    request.path(),
                    new Stored(request.requestHash(), Stored.State.IN_PROGRESS, now),
                    0,
                    null,
                    null,
                    now);
            return Optional.ofNullable(rows.putIfAbsent(request.partner() + "/" + request.requestId(), row));
        }

        @Override
        public void answered(
                String partner, String requestId, int status, String contentType, String sealedAnswer, Instant now) {
            change(
                    partner,
                    requestId,
                    row -> new Row(
                            partner,
                            requestId,
                            row.method(),
                            row.path(),
                            new Stored(
                                    row.stored().requestHash(),
                                    Stored.State.ANSWERED,
                                    row.stored().startedAt()),
                            status,
                            contentType,
                            sealedAnswer,
                            now));
        }

        @Override
        public void unknown(String partner, String requestId, Instant now) {
            change(
                    partner,
                    requestId,
                    row -> new Row(
                            partner,
                            requestId,
                            row.method(),
                            row.path(),
                            new Stored(
                                    row.stored().requestHash(),
                                    Stored.State.UNKNOWN,
                                    row.stored().startedAt()),
                            0,
                            null,
                            null,
                            now));
        }

        @Override
        public void release(String partner, String requestId) {
            if (failWrites) {
                throw new IllegalStateException("database down");
            }
            rows.remove(partner + "/" + requestId);
        }

        @Override
        public Optional<Row> find(String partner, String requestId) {
            return Optional.ofNullable(rows.get(partner + "/" + requestId));
        }

        @Override
        public int purgeOlderThan(Instant cutoff, int batch) {
            return 0;
        }

        private void change(String partner, String requestId, Function<Row, Row> update) {
            if (failWrites) {
                throw new IllegalStateException("database down");
            }
            rows.computeIfPresent(partner + "/" + requestId, (k, row) -> update.apply(row));
        }
    }
}
