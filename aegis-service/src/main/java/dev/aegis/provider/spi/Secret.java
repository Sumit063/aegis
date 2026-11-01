package dev.aegis.provider.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A secret value. It never prints itself, so a secret cannot leak through a log line, an exception
 * message or a configuration dump; and it compares in constant time.
 */
public record Secret(String value) {

    public Secret {
        Objects.requireNonNull(value, "value");
    }

    public byte[] bytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    public int length() {
        return value.length();
    }

    /** Constant-time comparison, for credentials presented by callers. */
    public boolean matches(String presented) {
        return presented != null && MessageDigest.isEqual(bytes(), presented.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "****";
    }
}
