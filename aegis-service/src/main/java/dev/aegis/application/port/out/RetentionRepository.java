package dev.aegis.application.port.out;

import java.time.Instant;

/** Deletes records past retention in small batches, so the purge never holds long locks. */
public interface RetentionRepository {

    /** @return rows deleted (payments, then validations no payment refers to) */
    int purgeOlderThan(Instant cutoff, int batch);
}
