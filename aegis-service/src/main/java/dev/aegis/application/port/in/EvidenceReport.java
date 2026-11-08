package dev.aegis.application.port.in;

import java.util.Map;

/**
 * Whether stored evidence can still be opened: how many sealed values each key holds, and how many of
 * the most recent ones were opened with the configured keys. Contents are never shown.
 *
 * @param sealedByKey sealed values per key ID; a key may be retired only when its count is 0
 * @param checked recent values opened
 * @param unreadable of those, values no configured key opens (missing key or tampered)
 */
public record EvidenceReport(Map<String, Long> sealedByKey, int checked, int unreadable) {}
