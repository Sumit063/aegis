package dev.aegis.application.service;

import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.in.UnknownConnectionException;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.Evidence;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.check.Gate;
import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderRegistry;
import dev.aegis.provider.spi.RawMessage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one received message into a verdict and a stored entry. Lock order is always validation, then
 * payment, so concurrent checks never deadlock; the domain {@link Gate} decides, this class only
 * loads, locks and stores. No network call happens here.
 */
@Service
public class CheckService implements CheckMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckService.class);

    private final DirectoryPort directory;
    private final ProviderRegistry providers;
    private final ValidationRepository validations;
    private final PaymentRepository payments;
    private final EvidenceCipherPort cipher;
    private final MetricsPort metrics;

    public CheckService(
            DirectoryPort directory,
            ProviderRegistry providers,
            ValidationRepository validations,
            PaymentRepository payments,
            EvidenceCipherPort cipher,
            MetricsPort metrics) {
        this.directory = directory;
        this.providers = providers;
        this.validations = validations;
        this.payments = payments;
        this.cipher = cipher;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public Result check(IngressRecord record) {
        Connection connection = directory
                .connection(record.connection())
                .orElseThrow(() -> new UnknownConnectionException(record.connection()));
        ProviderAdapter adapter = providers.adapter(connection.adapter()).orElseThrow();
        Evidence evidence = Evidence.of(flatten(record.headers()), record.sealedBody());
        if (!record.verified()) {
            return untrusted(connection, record, BlockReason.BAD_SIGNATURE, evidence);
        }
        RawMessage message = new RawMessage(record.headers(), cipher.open(record.sealedBody()));
        PaymentMessage parsed = Safely.parse(
                adapter,
                record.channel(),
                Safely.decrypt(adapter, record.channel(), message, connection),
                connection,
                record.receivedAt());
        if (parsed == null) {
            log.warn("unparseable message on {} (record {})", connection.name(), record.id());
            return untrusted(connection, record, BlockReason.UNPARSEABLE, evidence);
        }

        Validation validation = validationOf(parsed);
        // A message ID names one message for the whole provider: seen anywhere means a repeat,
        // counted where it was first stored (D25).
        Payment payment = payments.paymentOfMessage(parsed.provider(), parsed.id())
                .flatMap(payments::lock)
                .orElseGet(() -> payments.lockOrCreate(
                        parsed.provider(), parsed.paymentRef(), validation == null ? null : validation.id()));
        // Judged as of arrival (Aegis's own clock at Ingress), not when the Checker gets to it: a
        // backlog after an outage must not turn genuine messages into replays.
        Gate.Result result = Gate.receive(parsed, validation, payment, record.receivedAt(), adapter.policy());

        if (result.validation() != null && !result.validation().equals(validation)) {
            validations.update(result.validation());
        }
        Payment stored = payments.save(result.payment(), result.repeat() ? Map.of() : Map.of(parsed.id(), evidence));
        metrics.verdict(connection.name(), parsed.type(), result.verdict(), result.repeat());
        log.info(
                "{} {} {} payment={} {}",
                connection.name(),
                parsed.type(),
                result.repeat() ? "REPEAT" : result.verdict(),
                stored.id(),
                parsed.id());
        return new Result(
                result.verdict(),
                stored.id(),
                validation == null ? null : validation.merchantRef().value(),
                result.repeat());
    }

    /**
     * The validation a message is about, locked: by the provider reference it names, or (a refund
     * names only its payment) through the payment Aegis already linked to a validation. Always
     * locked before the payment, so concurrent checks never deadlock.
     */
    private Validation validationOf(PaymentMessage message) {
        if (message.providerRef() != null) {
            return validations.lock(message.provider(), message.providerRef()).orElse(null);
        }
        if (message.paymentRef() != null) {
            return payments.validationIdOf(message.provider(), message.paymentRef())
                    .flatMap(validations::lock)
                    .orElse(null);
        }
        return null;
    }

    private Result untrusted(Connection connection, IngressRecord record, BlockReason reason, Evidence evidence) {
        MessageType type = record.channel() == Channel.BROWSER ? MessageType.CALLBACK : MessageType.WEBHOOK;
        Payment stored = payments.insertUntrusted(
                Payment.untrusted(Ref.of(connection.name()), type, reason, record.receivedAt(), record.id()), evidence);
        metrics.verdict(connection.name(), type, Verdict.blocked(reason), false);
        log.warn("{} {} BLOCKED {} payment={}", connection.name(), type, reason, stored.id());
        return new Result(Verdict.blocked(reason), stored.id(), null, false);
    }

    private static Map<String, String> flatten(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(", ", e.getValue()), (a, b) -> a));
    }
}
