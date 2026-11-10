package dev.aegis.domain.partner;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One partner request as Aegis checks it.
 *
 * @param path the path below {@code /partner} with its query, as signed (e.g. {@code /recharges?x=1})
 * @param signedAt when the partner signed it
 * @param requestHash hex SHA-256 of method, path and body: the same request has the same hash
 */
public record PartnerRequest(
        String partner, String requestId, String method, String path, Instant signedAt, String requestHash) {

    /** Request IDs are path-, header- and log-safe. */
    public static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    // Plain segments only: no dot segments, no percent-encoding, nothing a server could re-interpret
    // into a path the partner was not granted.
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_~-][A-Za-z0-9._~-]*");

    public PartnerRequest {
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(signedAt, "signedAt");
        Objects.requireNonNull(requestHash, "requestHash");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("request ID must match " + REQUEST_ID);
        }
    }

    /** The path without its query: what may be kept in clear (a query can carry personal data). */
    public String route() {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /** The operation is the first path segment; empty when the path is not a plain path. */
    public Optional<String> operation() {
        String bare = route();
        if (!bare.startsWith("/") || bare.endsWith("/")) {
            return Optional.empty();
        }
        String[] segments = bare.substring(1).split("/", -1);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                return Optional.empty();
            }
        }
        return Optional.of(segments[0]);
    }
}
