package dev.aegis.adapter.in.web.dto;

import dev.aegis.provider.spi.RawMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A provider's HTTP request as Aegis keeps it: the exact body bytes (signatures cover them) and the
 * headers, minus credentials a browser may carry. Bodies over 64 KB are refused (LLD §7).
 */
public record InboundRequest(RawMessage message) {

    public static final int MAX_BODY = 64 * 1024;
    private static final Set<String> DROPPED = Set.of("cookie", "authorization", "proxy-authorization");

    public static InboundRequest read(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!DROPPED.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        return new InboundRequest(new RawMessage(headers, body(request)));
    }

    private static byte[] body(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                throw new BodyTooLargeException();
            }
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The body exceeds {@link #MAX_BODY} (HTTP 413). */
    public static final class BodyTooLargeException extends RuntimeException {
        BodyTooLargeException() {
            super("body larger than " + MAX_BODY + " bytes");
        }
    }
}
