package dev.aegis.application.service;

import dev.aegis.provider.spi.Secret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Partner request signatures (LLD §14): {@code X-Signature: v1=hex(HMAC-SHA256(secret, canonical))}
 * where canonical is {@code v1\n METHOD\n /partner/path?query\n timestamp\n request-id\n
 * hex(SHA-256(body))}. Everything that decides what the request does is signed.
 */
final class PartnerSignature {

    private static final String VERSION = "v1=";

    private PartnerSignature() {}

    static byte[] canonical(String method, String path, String timestamp, String requestId, byte[] body) {
        return String.join("\n", "v1", method, path, timestamp, requestId, sha256Hex(body))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Whether {@code header} is a v1 signature of {@code canonical} by any of {@code secrets}. */
    static boolean verify(List<Secret> secrets, String header, byte[] canonical) {
        if (header == null || !header.startsWith(VERSION)) {
            return false;
        }
        String presented = header.substring(VERSION.length());
        boolean valid = false;
        for (Secret secret : secrets) {
            // Every secret is tried, so the time taken does not tell which one matched.
            valid |= secret.signed(canonical, presented);
        }
        return valid;
    }

    static String sign(Secret secret, byte[] canonical) {
        return VERSION + secret.hmacSha256Hex(canonical);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
