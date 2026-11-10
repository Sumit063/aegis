package dev.aegis.application.service;

import dev.aegis.application.port.in.PartnerUseCase;
import dev.aegis.application.port.in.UnavailableException;
import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.application.port.out.Partner;
import dev.aegis.application.port.out.PartnerClientPort;
import dev.aegis.application.port.out.PartnerRequestRepository;
import dev.aegis.application.port.out.RateLimiterPort;
import dev.aegis.application.port.out.RateLimiterPort.Decision;
import dev.aegis.domain.partner.PartnerGate;
import dev.aegis.domain.partner.PartnerRequest;
import dev.aegis.domain.partner.Refusal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Carries a partner's request to its application (LLD §14), checks in order, cheapest first:
 * authenticate → fresh and granted → rates → claim the request ID → call the application → record its
 * answer. Nothing is stored before the partner is authenticated, and a request ID runs at most once:
 * the database's key on {@code (partner, request_id)} decides, whichever instance gets the request.
 */
@Service
public class PartnerService implements PartnerUseCase {

    private static final Logger log = LoggerFactory.getLogger(PartnerService.class);
    private static final String PROBLEM = "application/problem+json";
    private static final String UNKNOWN = "unknown";
    private static final String OTHER = "other";

    private final DirectoryPort directory;
    private final RateLimiterPort limiter;
    private final PartnerRequestRepository requests;
    private final PartnerClientPort client;
    private final EvidenceCipherPort cipher;
    private final MetricsPort metrics;
    private final PartnerSettings settings;
    private final Clock clock;

    public PartnerService(
            DirectoryPort directory,
            RateLimiterPort limiter,
            PartnerRequestRepository requests,
            PartnerClientPort client,
            EvidenceCipherPort cipher,
            MetricsPort metrics,
            PartnerSettings settings,
            Clock clock) {
        this.directory = directory;
        this.limiter = limiter;
        this.requests = requests;
        this.client = client;
        this.cipher = cipher;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Answer call(Call call) {
        Partner partner = call.partner() == null
                ? null
                : directory.partner(call.partner()).orElse(null);
        if (partner == null) {
            return refuse(UNKNOWN, OTHER, Refusal.UNAUTHENTICATED, null);
        }
        Optional<PartnerRequest> authenticated = authenticate(partner, call);
        if (authenticated.isEmpty()) {
            return refuse(partner.name(), OTHER, Refusal.UNAUTHENTICATED, null);
        }
        if (partner.clientCertSubject() != null && !partner.clientCertSubject().equals(call.certificateSubject())) {
            return refuse(partner.name(), OTHER, Refusal.WRONG_CERTIFICATE, null);
        }
        PartnerRequest request = authenticated.get();
        String operation = request.operation().orElse(null);
        Set<String> granted = partner.granted(operation);
        String tag = granted.isEmpty() ? OTHER : operation;

        Optional<Refusal> refused = PartnerGate.admit(request, granted, clock.instant(), settings.freshness());
        if (refused.isPresent()) {
            return refuse(partner.name(), tag, refused.get(), null);
        }
        Optional<Answer> limited = withinRates(partner, operation, tag);
        if (limited.isPresent()) {
            return limited.get();
        }

        Optional<PartnerRequestRepository.Row> known;
        try {
            known = requests.claim(request, cipher.seal(call.body()), clock.instant());
        } catch (RuntimeException e) {
            throw new UnavailableException("partner requests cannot be recorded", e);
        }
        if (known.isPresent()) {
            return repeat(known.get(), request, tag);
        }
        return execute(partner, request, call, tag);
    }

    private Optional<PartnerRequest> authenticate(Partner partner, Call call) {
        if (call.requestId() == null
                || !PartnerRequest.REQUEST_ID.matcher(call.requestId()).matches()
                || call.timestamp() == null
                || !call.timestamp().matches("[0-9]{1,12}")) {
            return Optional.empty();
        }
        String path = "/partner" + call.path();
        byte[] canonical =
                PartnerSignature.canonical(call.method(), path, call.timestamp(), call.requestId(), call.body());
        if (!PartnerSignature.verify(partner.secrets(), call.signature(), canonical)) {
            return Optional.empty();
        }
        String hash = PartnerSignature.sha256Hex(
                String.join("\n", call.method(), call.path(), PartnerSignature.sha256Hex(call.body()))
                        .getBytes(StandardCharsets.UTF_8));
        return Optional.of(new PartnerRequest(
                partner.name(),
                call.requestId(),
                call.method(),
                call.path(),
                Instant.ofEpochSecond(Long.parseLong(call.timestamp())),
                hash));
    }

    // The partner's own rate first, then the operation's: a refused request costs no operation allowance.
    private Optional<Answer> withinRates(Partner partner, String operation, String tag) {
        try {
            Decision decision = limiter.acquire("partner:" + partner.name(), partner.rate());
            if (decision.allowed()) {
                Partner.Grant grant = partner.operations().get(operation);
                decision = grant.rate() == null
                        ? Decision.ALLOWED
                        : limiter.acquire("partner:" + partner.name() + ":" + operation, grant.rate());
            }
            return decision.allowed()
                    ? Optional.empty()
                    : Optional.of(refuse(partner.name(), tag, Refusal.RATE_LIMIT, decision.retryAfter()));
        } catch (IllegalStateException e) {
            log.error("partner rate store unavailable: {}", e.getMessage());
            return Optional.of(refuse(partner.name(), tag, Refusal.LIMITS_UNAVAILABLE, Duration.ofSeconds(1)));
        }
    }

    private Answer repeat(PartnerRequestRepository.Row row, PartnerRequest request, String tag) {
        return switch (PartnerGate.repeat(row.stored(), request, clock.instant(), settings.answerTimeout())) {
            case REPLAY -> {
                metrics.partner(request.partner(), tag, "REPLAYED");
                log.info("partner {} request {} replayed", request.partner(), request.requestId());
                yield new Answer(
                        row.httpStatus(), row.contentType(), cipher.open(row.sealedAnswer()), true, null, null);
            }
            case IN_PROGRESS -> refuse(request.partner(), tag, Refusal.IN_PROGRESS, Duration.ofSeconds(1));
            case OUTCOME_UNKNOWN -> refuse(request.partner(), tag, Refusal.OUTCOME_UNKNOWN, null);
            case REUSED -> refuse(request.partner(), tag, Refusal.REQUEST_ID_REUSED, null);
        };
    }

    private Answer execute(Partner partner, PartnerRequest request, Call call, String tag) {
        Application application = directory.application(partner.application()).orElseThrow();
        Instant started = clock.instant();
        PartnerClientPort.Response response;
        try {
            response = client.send(
                    application,
                    new PartnerClientPort.Request(
                            partner.name(),
                            request.requestId(),
                            request.method(),
                            request.path(),
                            call.contentType(),
                            call.body()));
        } catch (PartnerClientPort.NotSentException e) {
            record(request, () -> requests.release(partner.name(), request.requestId()));
            log.warn("partner {} request {}: application not reached", partner.name(), request.requestId());
            return refuse(partner.name(), tag, Refusal.APPLICATION_UNAVAILABLE, Duration.ofSeconds(5));
        } catch (PartnerClientPort.NoAnswerException e) {
            record(request, () -> requests.unknown(partner.name(), request.requestId(), clock.instant()));
            log.warn("partner {} request {}: no answer, outcome unknown", partner.name(), request.requestId());
            return refuse(partner.name(), tag, Refusal.OUTCOME_UNKNOWN, null);
        }
        metrics.partnerUpstream(partner.name(), Duration.between(started, clock.instant()));
        String sealed = cipher.seal(response.body());
        record(
                request,
                () -> requests.answered(
                        partner.name(),
                        request.requestId(),
                        response.status(),
                        response.contentType(),
                        sealed,
                        clock.instant()));
        metrics.partner(partner.name(), tag, "ANSWERED");
        log.info(
                "partner {} request {} {} {} answered {}",
                partner.name(),
                request.requestId(),
                request.method(),
                tag,
                response.status());
        return new Answer(response.status(), response.contentType(), response.body(), false, null, null);
    }

    // The application has already acted: its answer still goes to the partner. A claim left in
    // progress is reported as OUTCOME_UNKNOWN once the answer timeout passes, which is the truth.
    private static void record(PartnerRequest request, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error(
                    "partner {} request {}: result not recorded: {}",
                    request.partner(),
                    request.requestId(),
                    e.getMessage());
        }
    }

    private Answer refuse(String partner, String operation, Refusal refusal, Duration retryAfter) {
        metrics.partner(partner, operation, refusal.name());
        log.info("partner {} {} refused {}", partner, operation, refusal);
        int status = status(refusal);
        String problem = """
                {"type":"about:blank","title":"Refused by Aegis","status":%d,"reason":"%s"}""".formatted(status, refusal);
        return new Answer(status, PROBLEM, problem.getBytes(StandardCharsets.UTF_8), false, refusal.name(), retryAfter);
    }

    static int status(Refusal refusal) {
        return switch (refusal) {
            case UNAUTHENTICATED, STALE -> 401;
            case WRONG_CERTIFICATE, NOT_PERMITTED -> 403;
            case IN_PROGRESS -> 409;
            case REQUEST_ID_REUSED -> 422;
            case RATE_LIMIT -> 429;
            case LIMITS_UNAVAILABLE, APPLICATION_UNAVAILABLE -> 503;
            case OUTCOME_UNKNOWN -> 504;
        };
    }
}
