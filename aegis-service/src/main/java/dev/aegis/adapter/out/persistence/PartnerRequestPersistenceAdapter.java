package dev.aegis.adapter.out.persistence;

import dev.aegis.application.port.out.PartnerRequestRepository;
import dev.aegis.domain.partner.PartnerRequest;
import dev.aegis.domain.partner.Stored;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code partner_requests} with plain SQL: a claim is one {@code INSERT … ON CONFLICT DO NOTHING}, so
 * two instances receiving the same request ID cannot both win. Updates only move a row out of
 * {@code IN_PROGRESS}, never back.
 */
@Component
class PartnerRequestPersistenceAdapter implements PartnerRequestRepository {

    private static final String COLUMNS =
            "partner, request_id, method, path, request_hash, state, http_status, content_type, response, created_at, updated_at";

    private final JdbcTemplate jdbc;

    PartnerRequestPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> claim(PartnerRequest request, String sealedBody, Instant now) {
        // A row released between our insert and our read frees the ID again: try once more.
        for (int attempt = 0; attempt < 3; attempt++) {
            int inserted = jdbc.update(
                    """
                    INSERT INTO partner_requests
                        (partner, request_id, request_hash, method, path, state, request, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?) ON CONFLICT DO NOTHING""",
                    request.partner(),
                    request.requestId(),
                    request.requestHash(),
                    request.method(),
                    request.route(),
                    sealedBody,
                    Timestamp.from(now),
                    Timestamp.from(now));
            if (inserted == 1) {
                return Optional.empty();
            }
            Optional<Row> existing = find(request.partner(), request.requestId());
            if (existing.isPresent()) {
                return existing;
            }
        }
        throw new IllegalStateException("request ID claimed and released repeatedly");
    }

    @Override
    public void answered(
            String partner, String requestId, int status, String contentType, String sealedAnswer, Instant now) {
        jdbc.update("""
                UPDATE partner_requests SET state = 'ANSWERED', http_status = ?, content_type = ?, response = ?,
                    updated_at = ?
                WHERE partner = ? AND request_id = ? AND state = 'IN_PROGRESS'""", status, contentType, sealedAnswer, Timestamp.from(now), partner, requestId);
    }

    @Override
    public void unknown(String partner, String requestId, Instant now) {
        jdbc.update("""
                UPDATE partner_requests SET state = 'UNKNOWN', updated_at = ?
                WHERE partner = ? AND request_id = ? AND state = 'IN_PROGRESS'""", Timestamp.from(now), partner, requestId);
    }

    @Override
    public void release(String partner, String requestId) {
        jdbc.update(
                "DELETE FROM partner_requests WHERE partner = ? AND request_id = ? AND state = 'IN_PROGRESS'",
                partner,
                requestId);
    }

    @Override
    public Optional<Row> find(String partner, String requestId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM partner_requests WHERE partner = ? AND request_id = ?",
                        PartnerRequestPersistenceAdapter::row,
                        partner,
                        requestId)
                .stream()
                .findFirst();
    }

    @Override
    public int purgeOlderThan(Instant cutoff, int batch) {
        return jdbc.update("""
                DELETE FROM partner_requests WHERE ctid IN (
                    SELECT ctid FROM partner_requests WHERE updated_at < ? LIMIT ?)""", Timestamp.from(cutoff), batch);
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getString("partner"),
                rs.getString("request_id"),
                rs.getString("method"),
                rs.getString("path"),
                new Stored(
                        rs.getString("request_hash"),
                        Stored.State.valueOf(rs.getString("state")),
                        rs.getTimestamp("created_at").toInstant()),
                rs.getInt("http_status"),
                rs.getString("content_type"),
                rs.getString("response"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
