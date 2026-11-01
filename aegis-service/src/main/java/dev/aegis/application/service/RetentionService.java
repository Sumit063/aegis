package dev.aegis.application.service;

import dev.aegis.application.port.in.RetentionUseCase;
import dev.aegis.application.port.out.RetentionRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps records for 180 days: far beyond any provider's retry window (Razorpay: 24 h), so a message
 * that is purged can never come back as a replay that looks new.
 */
@Service
public class RetentionService implements RetentionUseCase {

    static final Duration RETENTION = Duration.ofDays(180);
    private static final int BATCH = 1000;
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final RetentionRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RetentionService(RetentionRepository repository, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int purge() {
        var cutoff = clock.instant().minus(RETENTION);
        int total = 0;
        int deleted;
        do {
            deleted = tx.execute(status -> repository.purgeOlderThan(cutoff, BATCH));
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("retention: deleted {} rows older than {}", total, cutoff);
        }
        return total;
    }
}
