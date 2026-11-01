package dev.aegis.application.service;

import dev.aegis.application.port.in.ForwardUseCase;
import dev.aegis.application.port.in.SettingsUseCase;
import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.ForwardClientPort;
import dev.aegis.application.port.out.ForwardClientPort.Delivery;
import dev.aegis.application.port.out.ForwardRepository;
import dev.aegis.application.port.out.ForwardRepository.Attempt;
import dev.aegis.application.port.out.ForwardRepository.Claimed;
import dev.aegis.application.port.out.ForwardRepository.Pending;
import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.payment.Validation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers passed messages to their application. Claims due payments in a short transaction, sends
 * outside any transaction, then records each attempt. Payments are delivered in parallel on virtual
 * threads; messages of one payment strictly in order, stopping at the first that is not delivered.
 *
 * <p>Retries: exponential backoff from 1 s, capped at 10 minutes, with jitter, for 72 hours; then the
 * message is FAILED, alerted, and can be re-forwarded from Admin.
 */
@Service
public class ForwardService implements ForwardUseCase {

    static final int BATCH = 50;
    static final Duration LEASE = Duration.ofMinutes(1);
    static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY = Duration.ofMinutes(10);
    static final Duration GIVE_UP_AFTER = Duration.ofHours(72);
    static final Duration PAUSED_RECHECK = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(ForwardService.class);

    private final ForwardRepository queue;
    private final SettingsUseCase settings;
    private final ValidationRepository validations;
    private final DirectoryPort directory;
    private final ForwardClientPort client;
    private final EvidenceCipherPort cipher;
    private final MetricsPort metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ForwardService(
            ForwardRepository queue,
            SettingsUseCase settings,
            ValidationRepository validations,
            DirectoryPort directory,
            ForwardClientPort client,
            EvidenceCipherPort cipher,
            MetricsPort metrics,
            TransactionTemplate tx,
            Clock clock) {
        this.queue = queue;
        this.settings = settings;
        this.validations = validations;
        this.directory = directory;
        this.client = client;
        this.cipher = cipher;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int forwardDue() {
        List<Claimed> claimed = tx.execute(status -> queue.claimDue(clock.instant(), BATCH, LEASE));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = claimed.stream()
                    .map(payment -> threads.submit(() -> deliver(payment)))
                    .toList();
            for (Future<Integer> result : results) {
                delivered += result.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("forwarding pass failed", e.getCause());
        }
        return delivered;
    }

    private int deliver(Claimed payment) {
        var connection = directory.connection(payment.provider().value()).orElse(null);
        Application application = connection == null
                ? null
                : directory.application(connection.application()).orElse(null);
        Validation validation = payment.validationId() == null
                ? null
                : validations.find(payment.validationId()).orElse(null);
        if (application != null && settings.current().pausedApplications().contains(application.name())) {
            // Held by an operator: nothing is lost, it is looked at again shortly.
            tx.executeWithoutResult(
                    status -> queue.release(payment.paymentId(), clock.instant().plus(PAUSED_RECHECK)));
            return 0;
        }
        int delivered = 0;
        for (Pending message : payment.pending()) {
            Instant now = clock.instant();
            if (message.nextAt() != null && message.nextAt().isAfter(now)) {
                break; // an earlier message is waiting for its retry; later ones wait behind it
            }
            String error = application == null
                    ? "no application for connection " + payment.provider()
                    : client.deliver(application, delivery(payment, message, validation));
            Attempt attempt = error == null ? new Attempt(true, now, null, null) : retry(message, now, error);
            tx.executeWithoutResult(status -> queue.record(payment.paymentId(), message.n(), attempt));
            metrics.forwarded(
                    application == null ? "unknown" : application.name(),
                    attempt.delivered(),
                    !attempt.delivered() && attempt.nextAt() == null);
            if (error != null) {
                log.warn(
                        "forward {}-{} not delivered (attempt {}): {}",
                        payment.paymentId(),
                        message.n(),
                        message.attempts() + 1,
                        error);
                break;
            }
            delivered++;
        }
        return delivered;
    }

    private Delivery delivery(Claimed payment, Pending message, Validation validation) {
        return new Delivery(
                payment.paymentId() + "-" + message.n(),
                "PASSED",
                payment.provider().value(),
                message.type().name(),
                message.outcome().name(),
                validation == null ? null : validation.merchantRef().value(),
                validation == null || validation.providerRef() == null
                        ? null
                        : validation.providerRef().value(),
                payment.paymentRef() == null ? null : payment.paymentRef().value(),
                validation == null ? null : validation.amount(),
                message.headers(),
                message.sealedBody() == null ? new byte[0] : cipher.open(message.sealedBody()));
    }

    static Attempt retry(Pending message, Instant now, String error) {
        Instant first = message.firstAttemptAt() == null ? now : message.firstAttemptAt();
        if (now.isAfter(first.plus(GIVE_UP_AFTER))) {
            return new Attempt(false, now, null, error);
        }
        long exponential = FIRST_RETRY.toMillis() << Math.min(message.attempts(), 20);
        long capped = Math.min(exponential, MAX_RETRY.toMillis());
        long jittered = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return new Attempt(false, now, now.plusMillis(jittered), error);
    }
}
