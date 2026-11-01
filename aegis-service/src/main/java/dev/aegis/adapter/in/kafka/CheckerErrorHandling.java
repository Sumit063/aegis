package dev.aegis.adapter.in.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens when a record cannot be processed (HLD §9, HARDENING §3):
 *
 * <ul>
 *   <li><b>A dependency is down</b> (the database): retry with backoff up to 30 s, forever. The
 *       partition pauses and messages wait in Kafka; nothing is skipped.
 *   <li><b>The record itself is bad</b> (cannot be read, violates a constraint): three attempts, then
 *       it goes to {@code aegis.inbound.dlt} and the partition moves on. Counted as
 *       {@code aegis_dead_letters_total} and alerted; replayed by an operator after a fix.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CheckerErrorHandling {

    static final String DEAD_LETTERS = "aegis.inbound.dlt";

    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTERS).partitions(6).replicas(1).build();
    }

    @Bean
    CommonErrorHandler checkerErrorHandler(KafkaOperations<String, byte[]> kafka, MeterRegistry metrics) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafka, (record, e) -> new TopicPartition(DEAD_LETTERS, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, e) -> {
                    metrics.counter("aegis.dead.letters").increment();
                    recoverer.accept(record, e);
                },
                new FixedBackOff(1_000, 2));
        handler.setBackOffFunction((record, e) -> transientFailure(e) ? forever() : null);
        return handler;
    }

    private static BackOff forever() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        return backOff;
    }

    /**
     * An outage, not a bad record. Errs on the side of "outage": retrying a bad record forever only
     * pauses one partition (and is alerted by lag); dead-lettering a good one would take it out of
     * the normal flow.
     */
    static boolean transientFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException
                    || cause instanceof ConnectException
                    || (cause instanceof SQLException sql && connectionState(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    // SQLSTATE classes 08 (connection), 53 (insufficient resources), 57 (operator intervention).
    private static boolean connectionState(String state) {
        return state != null && (state.startsWith("08") || state.startsWith("53") || state.startsWith("57"));
    }
}
