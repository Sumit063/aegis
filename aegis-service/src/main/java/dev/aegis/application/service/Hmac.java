package dev.aegis.application.service;

import dev.aegis.provider.spi.Secret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Aegis's own signatures towards applications: lower-case hex HMAC-SHA256 (LLD §8). */
final class Hmac {

    private Hmac() {}

    static String sha256Hex(Secret key, String data) {
        return sha256Hex(key, data.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(Secret key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.bytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
