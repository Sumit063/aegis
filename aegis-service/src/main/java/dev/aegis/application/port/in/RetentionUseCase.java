package dev.aegis.application.port.in;

/** Deletes records older than the retention period (LLD §7: 180 days). */
public interface RetentionUseCase {

    /** @return rows deleted */
    int purge();
}
