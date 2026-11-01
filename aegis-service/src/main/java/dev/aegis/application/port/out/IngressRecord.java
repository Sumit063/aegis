package dev.aegis.application.port.out;

import dev.aegis.provider.spi.Channel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A provider message as Ingress wrote it to the log: exact headers, the body sealed with the evidence
 * key (the log never holds clear text), and whether its signature verified.
 *
 * @param key keeps messages about one payment in order (payment reference when readable)
 */
public record IngressRecord(
        UUID id,
        String connection,
        Channel channel,
        Instant receivedAt,
        boolean verified,
        String key,
        Map<String, List<String>> headers,
        String sealedBody) {

    public IngressRecord {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
