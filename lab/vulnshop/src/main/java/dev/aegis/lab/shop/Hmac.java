package dev.aegis.lab.shop;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Hex HMAC-SHA256, compared in constant time. */
final class Hmac {

    private Hmac() {}

    static String sha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An empty key is exactly what flaw F1 accepts; the JDK refuses it, so use a single zero byte,
            // which HMAC pads to the same block as an empty key.
            byte[] key = secret.isEmpty() ? new byte[1] : secret.getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static boolean matches(String secret, String data, String signature) {
        return signature != null
                && MessageDigest.isEqual(
                        sha256Hex(secret, data).getBytes(StandardCharsets.UTF_8),
                        signature.getBytes(StandardCharsets.UTF_8));
    }
}
