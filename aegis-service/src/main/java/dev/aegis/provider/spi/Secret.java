package dev.aegis.provider.spi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    /** HMAC-SHA256 of {@code data} with this secret as the key. */
    public byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bytes(), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Hex HMAC-SHA256 of {@code data} with this secret as the key. */
    public String hmacSha256Hex(byte[] data) {
        return HexFormat.of().formatHex(hmacSha256(data));
    }

    /** Whether {@code presented} is the hex HMAC-SHA256 of {@code data} with this secret (constant time). */
    public boolean signed(byte[] data, String presented) {
        return presented != null
                && MessageDigest.isEqual(
                        hmacSha256Hex(data).getBytes(StandardCharsets.UTF_8),
                        presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "****";
    }
}
