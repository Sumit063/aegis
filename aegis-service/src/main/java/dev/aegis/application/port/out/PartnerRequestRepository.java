package dev.aegis.application.port.out;

import dev.aegis.domain.partner.PartnerRequest;
import dev.aegis.domain.partner.Stored;
import java.time.Instant;
import java.util.Optional;

/**
 * One row per partner and request ID ({@code partner_requests}, LLD §7): the database's primary key
 * is what makes a request ID execute at most once, across instances.
 */
public interface PartnerRequestRepository {

    /** A request ID's row. Bodies are sealed. */
    record Row(
            String partner,
            String requestId,
            String method,
            String path,
            Stored stored,
            int httpStatus,
            String contentType,
            String sealedAnswer,
            Instant updatedAt) {}

    /**
     * Records {@code request} as in progress unless its ID is already known.
     *
     * @return empty if this call claimed the ID, otherwise the row that holds it
     */
    Optional<Row> claim(PartnerRequest request, String sealedBody, Instant now);

    void answered(String partner, String requestId, int status, String contentType, String sealedAnswer, Instant now);

    void unknown(String partner, String requestId, Instant now);

    /** Forgets a claim whose request never reached the application, so a retry can run. */
    void release(String partner, String requestId);

    Optional<Row> find(String partner, String requestId);

    /** @return rows deleted, at most {@code batch}, whose last activity is before {@code cutoff} */
    int purgeOlderThan(Instant cutoff, int batch);
}
