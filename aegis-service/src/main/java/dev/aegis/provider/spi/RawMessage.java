package dev.aegis.provider.spi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bytes exactly as received or sent, with their headers. Signatures are computed over these exact
 * bytes, so nothing is parsed or re-serialised before verification.
 *
 * <p>Header names keep their original spelling; lookups ignore case (HTTP header names are
 * case-insensitive).
 */
public record RawMessage(Map<String, List<String>> headers, byte[] body) {

    public RawMessage {
        Objects.requireNonNull(body, "body");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        }
        headers = Collections.unmodifiableMap(copy);
    }

    public static RawMessage of(Map<String, String> headers, String body) {
        Map<String, List<String>> multi = new LinkedHashMap<>();
        headers.forEach((name, value) -> multi.put(name, List.of(value)));
        return new RawMessage(multi, body.getBytes(StandardCharsets.UTF_8));
    }

    /** The first value of a header, ignoring the case of its name. */
    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty())
                .map(e -> e.getValue().getFirst())
                .findFirst();
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RawMessage that && headers.equals(that.headers) && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return 31 * headers.hashCode() + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "RawMessage[" + body.length + " bytes, headers=" + headers.keySet() + "]";
    }
}
