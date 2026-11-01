package dev.aegis.application.port.out;

import java.util.Map;

/**
 * What is kept of one message besides its entry: the headers as received and the sealed body (and,
 * for an outbound call, the sealed reply). Bodies are already encrypted when they get here.
 *
 * @param reply sealed reply of an outbound call, or null
 * @param httpStatus the provider's HTTP status for an outbound call, or 0
 */
public record Evidence(Map<String, String> headers, String body, String reply, int httpStatus) {

    public Evidence {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static Evidence of(Map<String, String> headers, String body) {
        return new Evidence(headers, body, null, 0);
    }
}
