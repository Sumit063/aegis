package dev.aegis.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AesGcmEvidenceCipherTest {

    private static final byte[] K1 = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] K2 = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

    private final AesGcmEvidenceCipher cipher = new AesGcmEvidenceCipher(Map.of("k1", K1), "k1");

    @Test
    void sealsAndOpens() {
        String sealed = cipher.seal(BODY);

        assertThat(sealed).startsWith("enc:v1:k1:").doesNotContain("payment.captured");
        assertThat(cipher.open(sealed)).isEqualTo(BODY);
    }

    @Test
    void usesAFreshIvEveryTime() {
        assertThat(cipher.seal(BODY)).isNotEqualTo(cipher.seal(BODY));
    }

    @Test
    void detectsTampering() {
        String sealed = cipher.seal(BODY);
        char last = sealed.charAt(sealed.length() - 3);
        String tampered = sealed.substring(0, sealed.length() - 3)
                + (last == 'A' ? 'B' : 'A')
                + sealed.substring(sealed.length() - 2);

        assertThatThrownBy(() -> cipher.open(tampered)).hasMessageContaining("tampered");
    }

    @Test
    void readsOldValuesAfterRotation() {
        String old = cipher.seal(BODY);
        AesGcmEvidenceCipher rotated = new AesGcmEvidenceCipher(Map.of("k1", K1, "k2", K2), "k2");

        assertThat(rotated.open(old)).isEqualTo(BODY);
        assertThat(rotated.seal(BODY)).startsWith("enc:v1:k2:");
    }

    @Test
    void refusesUnknownKeysAndBadConfiguration() {
        String sealedWithK2 = new AesGcmEvidenceCipher(Map.of("k2", K2), "k2").seal(BODY);

        assertThatThrownBy(() -> cipher.open(sealedWithK2)).hasMessageContaining("unknown key");
        assertThatThrownBy(() -> cipher.open("plain text")).hasMessageContaining("not sealed");
        assertThatThrownBy(() -> new AesGcmEvidenceCipher(Map.of("k1", new byte[16]), "k1"))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AesGcmEvidenceCipher(Map.of("k1", K1), "k9"))
                .hasMessageContaining("not configured");
    }
}
