package dev.aegis.provider.spi;

import java.nio.charset.StandardCharsets;

/** The response a provider expects once its message is safely stored: a 2xx, or e.g. {@code <Ack/>}. */
public record Ack(int status, String contentType, byte[] body) {

    public static final Ack OK = new Ack(200, "text/plain", "OK".getBytes(StandardCharsets.UTF_8));
}
