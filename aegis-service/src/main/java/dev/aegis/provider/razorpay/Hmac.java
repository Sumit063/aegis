package dev.aegis.provider.razorpay;

import dev.aegis.provider.spi.Secret;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Razorpay's signature: lower-case hex HMAC-SHA256. Configuration guarantees the key is not empty. */
final class Hmac {

    private Hmac() {}

    static String sha256Hex(Secret secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.bytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
