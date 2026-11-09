package dev.aegis.application.service;

import dev.aegis.application.port.in.BadRequestException;
import dev.aegis.application.port.in.ForbiddenException;
import dev.aegis.application.port.in.GatewayUseCase;
import dev.aegis.application.port.in.UnavailableException;
import dev.aegis.application.port.in.UnknownConnectionException;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.Evidence;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ProviderClientPort;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.check.Gate;
import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.CallFacts;
import dev.aegis.provider.spi.CallKind;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.PreparedCall;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderRegistry;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.UnparseableMessageException;
import dev.aegis.provider.spi.UntrustedReplyException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carries an application's call to its provider (LLD §4): check, sign, send, verify the reply, record.
 * Database work happens in short transactions before and after the call; the network call itself is
 * never inside one (invariant 6).
 *
 * <p>A payment request is written as in flight <em>before</em> it is sent, under the validation's
 * lock, so two concurrent requests for one validation can never both go out (LLD T10).
 */
@Service
public class GatewayService implements GatewayUseCase {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private static final String PROBLEM = "application/problem+json";

    private final DirectoryPort directory;
    private final ProviderRegistry providers;
    private final ProviderClientPort client;
    private final ValidationRepository validations;
    private final PaymentRepository payments;
    private final EvidenceCipherPort cipher;
    private final MetricsPort metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GatewayService(
            DirectoryPort directory,
            ProviderRegistry providers,
            ProviderClientPort client,
            ValidationRepository validations,
            PaymentRepository payments,
            EvidenceCipherPort cipher,
            MetricsPort metrics,
            TransactionTemplate tx,
            Clock clock) {
        this.directory = directory;
        this.providers = providers;
        this.client = client;
        this.validations = validations;
        this.payments = payments;
        this.cipher = cipher;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Answer call(String application, String connectionName, CallKind kind, RawMessage request) {
        Connection connection =
                directory.connection(connectionName).orElseThrow(() -> new UnknownConnectionException(connectionName));
        if (!connection.application().equals(application)) {
            throw new ForbiddenException("connection " + connectionName + " belongs to another application");
        }
        ProviderAdapter adapter = providers.adapter(connection.adapter()).orElseThrow();
        if (!adapter.supports(kind)) {
            throw new BadRequestException(connection.adapter() + " does not support " + kind);
        }
        CallFacts facts;
        try {
            facts = adapter.facts(kind, request);
        } catch (UnparseableMessageException e) {
            throw new BadRequestException(e.getMessage());
        }
        Answer answer = switch (kind) {
            case VALIDATION -> validate(connection, adapter, request, facts);
            case PAY_REQUEST -> pay(connection, adapter, request, facts);
            case STATUS_CHECK -> checkStatus(connection, adapter, request, facts);
        };
        if (kind != CallKind.VALIDATION) {
            metrics.verdict(
                    connection.name(),
                    kind == CallKind.PAY_REQUEST ? MessageType.PAY_REQUEST : MessageType.STATUS_CHECK,
                    answer.verdict(),
                    false);
        }
        return answer;
    }

    private Answer validate(Connection connection, ProviderAdapter adapter, RawMessage request, CallFacts facts) {
        Instant requestedAt = clock.instant();
        ProviderClientPort.Response response = send(adapter.prepare(CallKind.VALIDATION, request, connection));
        Instant answeredAt = clock.instant();
        Reply.Validated reply;
        Verdict verdict = Verdict.PASSED;
        try {
            reply = (Reply.Validated) adapter.reply(
                    CallKind.VALIDATION, request, response.status(), raw(response), connection, answeredAt);
        } catch (UntrustedReplyException e) {
            reply = new Reply.Validated(false, null, null);
            verdict = Verdict.blocked(BlockReason.BAD_SIGNATURE);
        } catch (UnparseableMessageException e) {
            reply = new Reply.Validated(false, null, null);
            verdict = Verdict.blocked(BlockReason.UNPARSEABLE);
        }
        Validation validation = new Validation(
                null,
                Ref.of(connection.name()),
                facts.merchantRef(),
                reply.providerRef(),
                reply.amount(),
                reply.accepted() ? Validation.Status.CREATED : Validation.Status.FAILED);
        Evidence exchange = new Evidence(
                headers(request), cipher.seal(request.body()), cipher.seal(response.body()), response.status());
        Validation stored = validations.insert(validation, exchange, requestedAt, answeredAt);
        log.info(
                "{} VALIDATION {} {} validation={}",
                connection.name(),
                facts.merchantRef(),
                stored.status(),
                stored.id());
        return new Answer(true, response.status(), response.contentType(), response.body(), verdict);
    }

    private Answer pay(Connection connection, ProviderAdapter adapter, RawMessage request, CallFacts facts) {
        if (facts.providerRef() == null || facts.paymentRef() == null || facts.amount() == null) {
            throw new BadRequestException(
                    "a payment request needs the validation reference, a payment reference and the amount");
        }
        Ref provider = Ref.of(connection.name());
        Instant now = clock.instant();
        PaymentMessage outgoing = new PaymentMessage(
                provider,
                MessageType.PAY_REQUEST,
                Ref.of("req:" + facts.paymentRef()),
                facts.providerRef(),
                facts.paymentRef(),
                Outcome.PENDING,
                facts.amount(),
                null,
                now);
        Evidence evidence = Evidence.of(headers(request), cipher.seal(request.body()));
        Reservation reservation = tx.execute(status -> {
            Validation validation =
                    validations.lock(provider, facts.providerRef()).orElse(null);
            List<Payment> siblings = validation == null ? List.of() : payments.ofValidation(validation.id());
            Verdict verdict = Gate.permit(outgoing, validation, null, siblings, now, adapter.enquiryLimits());
            if (!verdict.passed()) {
                return new Reservation(verdict, null, 0);
            }
            Payment reserved = payments.save(
                    payments.lockOrCreate(provider, facts.paymentRef(), validation.id())
                            .append(outgoing, Verdict.PASSED),
                    Map.of(outgoing.id(), evidence));
            return new Reservation(
                    Verdict.PASSED, reserved.id(), reserved.entries().size());
        });
        if (!reservation.verdict().passed()) {
            log.info("{} PAY_REQUEST {} {}", connection.name(), facts.paymentRef(), reservation.verdict());
            return refused(reservation.verdict());
        }
        // Sent with no transaction open. If this fails, the request stays in flight until a status
        // check or a result says otherwise; Aegis never decides on its own that it expired.
        ProviderClientPort.Response response = send(adapter.prepare(CallKind.PAY_REQUEST, request, connection));
        tx.executeWithoutResult(status -> payments.attachReply(
                reservation.paymentId(), reservation.entry(), cipher.seal(response.body()), response.status()));
        log.info(
                "{} PAY_REQUEST {} sent, provider answered {}",
                connection.name(),
                facts.paymentRef(),
                response.status());
        return new Answer(true, response.status(), response.contentType(), response.body(), Verdict.PASSED);
    }

    private Answer checkStatus(Connection connection, ProviderAdapter adapter, RawMessage request, CallFacts facts) {
        if (facts.paymentRef() == null) {
            throw new BadRequestException("a status check needs a payment reference");
        }
        Ref provider = Ref.of(connection.name());
        PaymentMessage asked = new PaymentMessage(
                provider,
                MessageType.STATUS_CHECK,
                Ref.of("ask:" + UUID.randomUUID()),
                facts.providerRef(),
                facts.paymentRef(),
                Outcome.OTHER,
                null,
                null,
                clock.instant());
        Verdict permitted = tx.execute(status -> Gate.permit(
                asked,
                null,
                payments.lock(provider, facts.paymentRef()).orElse(null),
                List.of(),
                clock.instant(),
                adapter.enquiryLimits()));
        if (!permitted.passed()) {
            log.info("{} STATUS_CHECK {} {}", connection.name(), facts.paymentRef(), permitted);
            return refused(permitted);
        }

        ProviderClientPort.Response response = send(adapter.prepare(CallKind.STATUS_CHECK, request, connection));
        Instant answeredAt = clock.instant();
        PaymentMessage answer;
        try {
            answer = ((Reply.Payment) adapter.reply(
                            CallKind.STATUS_CHECK, request, response.status(), raw(response), connection, answeredAt))
                    .message();
        } catch (UntrustedReplyException e) {
            return new Answer(
                    true,
                    response.status(),
                    response.contentType(),
                    response.body(),
                    Verdict.blocked(BlockReason.BAD_SIGNATURE));
        } catch (UnparseableMessageException e) {
            return new Answer(
                    true,
                    response.status(),
                    response.contentType(),
                    response.body(),
                    Verdict.blocked(BlockReason.UNPARSEABLE));
        }
        Evidence evidence = new Evidence(
                headers(request), cipher.seal(request.body()), cipher.seal(response.body()), response.status());
        Verdict verdict = tx.execute(status -> {
            Validation validation = answer.providerRef() == null
                    ? null
                    : validations.lock(provider, answer.providerRef()).orElse(null);
            Payment payment =
                    payments.lockOrCreate(provider, answer.paymentRef(), validation == null ? null : validation.id());
            Gate.Result result = Gate.receive(answer, validation, payment, answeredAt, adapter.policy());
            if (result.validation() != null && !result.validation().equals(validation)) {
                validations.update(result.validation());
            }
            payments.save(result.payment(), Map.of(answer.id(), evidence));
            return result.verdict();
        });
        log.info("{} STATUS_CHECK {} {} {}", connection.name(), facts.paymentRef(), answer.outcome(), verdict);
        return new Answer(true, response.status(), response.contentType(), response.body(), verdict);
    }

    private ProviderClientPort.Response send(PreparedCall call) {
        try {
            return client.send(call);
        } catch (RuntimeException e) {
            throw new UnavailableException("provider unreachable", e);
        }
    }

    private static Answer refused(Verdict verdict) {
        int status = verdict.reason() == BlockReason.RATE_LIMIT ? 429 : 409;
        String problem = """
                {"type":"about:blank","title":"Blocked by Aegis","status":%d,"reason":"%s"}""".formatted(status, verdict.reason());
        return new Answer(false, status, PROBLEM, problem.getBytes(StandardCharsets.UTF_8), verdict);
    }

    private static RawMessage raw(ProviderClientPort.Response response) {
        return new RawMessage(response.headers(), response.body());
    }

    // The application's credential for Aegis is never stored.
    private static Map<String, String> headers(RawMessage request) {
        return request.headers().entrySet().stream()
                .filter(e -> !e.getKey().equalsIgnoreCase("authorization")
                        && !e.getKey().equalsIgnoreCase("cookie"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(", ", e.getValue()), (a, b) -> a));
    }

    private record Reservation(Verdict verdict, Long paymentId, int entry) {}
}
