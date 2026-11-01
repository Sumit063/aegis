package dev.aegis.application.service;

import dev.aegis.application.port.in.ReceiveProviderMessageUseCase;
import dev.aegis.application.port.in.UnavailableException;
import dev.aegis.application.port.in.UnknownConnectionException;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressLogPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderRegistry;
import dev.aegis.provider.spi.RawMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ingress: transport-level work only, so the provider gets its answer in milliseconds. The checks that
 * need the database run in the Checker. A forged message is still logged (flagged), so it is recorded
 * as {@code BLOCKED BAD_SIGNATURE}, but it never gets an acknowledgement.
 */
@Service
public class IngressService implements ReceiveProviderMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngressService.class);

    private final DirectoryPort directory;
    private final ProviderRegistry providers;
    private final IngressLogPort ingressLog;
    private final EvidenceCipherPort cipher;
    private final MetricsPort metrics;
    private final TokenBucket forgeries;
    private final Clock clock;

    public IngressService(
            DirectoryPort directory,
            ProviderRegistry providers,
            IngressLogPort ingressLog,
            EvidenceCipherPort cipher,
            MetricsPort metrics,
            IngressLimits limits,
            Clock clock) {
        this.directory = directory;
        this.providers = providers;
        this.ingressLog = ingressLog;
        this.cipher = cipher;
        this.metrics = metrics;
        this.forgeries = new TokenBucket(limits.forgeriesPerMinute(), limits.forgeriesPerMinute(), clock);
        this.clock = clock;
    }

    @Override
    public Receipt receive(String connectionName, Channel channel, RawMessage message) {
        Connection connection =
                directory.connection(connectionName).orElseThrow(() -> new UnknownConnectionException(connectionName));
        ProviderAdapter adapter = providers.adapter(connection.adapter()).orElseThrow();
        Instant receivedAt = clock.instant();
        RawMessage clear = Safely.decrypt(adapter, channel, message, connection);
        boolean verified = clear != null && Safely.verify(adapter, channel, clear, connection);
        IngressRecord record = new IngressRecord(
                UUID.randomUUID(),
                connection.name(),
                channel,
                receivedAt,
                verified,
                verified ? key(adapter, channel, clear, connection, receivedAt) : connection.name(),
                message.headers(),
                cipher.seal(message.body()));
        if (!verified && !forgeries.tryAcquire(connection.name())) {
            // Over the evidence quota: refused and counted, not stored, so a flood of forgeries cannot
            // fill Kafka, the database or the disk (HARDENING §3).
            metrics.acknowledged(connection.name(), false, Duration.between(receivedAt, clock.instant()));
            return new Receipt(false, null);
        }
        try {
            ingressLog.append(record);
        } catch (RuntimeException e) {
            if (!verified) {
                // A forgery need not be stored to be refused.
                log.warn("ingress log unavailable; refusing unverified message on {}", connection.name());
                return new Receipt(false, null);
            }
            throw new UnavailableException("ingress log unavailable", e);
        }
        metrics.acknowledged(connection.name(), verified, Duration.between(receivedAt, clock.instant()));
        if (!verified) {
            log.warn("bad signature on {} ({})", connection.name(), channel);
            return new Receipt(false, null);
        }
        return new Receipt(true, adapter.ack(channel, clear));
    }

    // Messages about one payment share a key, so the log keeps them in order.
    private static String key(
            ProviderAdapter adapter, Channel channel, RawMessage message, Connection connection, Instant at) {
        PaymentMessage parsed = Safely.parse(adapter, channel, message, connection, at);
        if (parsed == null) {
            return connection.name();
        }
        return connection.name() + ":"
                + (parsed.paymentRef() != null
                        ? parsed.paymentRef()
                        : parsed.providerRef() != null ? parsed.providerRef() : "-");
    }
}
