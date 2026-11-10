package dev.aegis.application.port.in;

import java.time.Instant;

/**
 * What operators see of one partner request: never its body, query or answer.
 *
 * @param path below {@code /partner}, without the query
 * @param httpStatus the application's answer status, or 0 when there is none
 */
public record PartnerRequestView(
        String partner,
        String requestId,
        String method,
        String path,
        String state,
        int httpStatus,
        Instant startedAt,
        Instant updatedAt) {}
