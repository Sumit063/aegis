package dev.aegis.adapter.out.crypto;

import dev.aegis.application.port.out.EvidenceCipherPort;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for stored message bodies (LLD §7). Output: {@code enc:v1:<key id>:<base64(iv ‖
 * ciphertext ‖ tag)>} with a fresh 96-bit IV per value. The key ID lets old values be read after a
 * new key becomes current.
 */
public final class AesGcmEvidenceCipher implements EvidenceCipherPort {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> keys;
    private final String current;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keys key ID to raw 32-byte key
     * @param current the key ID new values are sealed with
     */
    public AesGcmEvidenceCipher(Map<String, byte[]> keys, String current) {
        if (!keys.containsKey(current)) {
            throw new IllegalArgumentException("current key " + current + " is not configured");
        }
        keys.forEach((id, key) -> {
            if (key.length != 32 || id.contains(":")) {
                throw new IllegalArgumentException("evidence key " + id + " must be 32 bytes with an ID without ':'");
            }
        });
        this.keys = keys.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> new SecretKeySpec(e.getValue(), "AES")));
        this.current = current;
    }

    @Override
    public String seal(byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(current), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain);
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv)
                    .put(sealed)
                    .array();
            return PREFIX + current + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("evidence encryption failed", e);
        }
    }

    @Override
    public byte[] open(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not sealed evidence");
        }
        String rest = sealed.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        SecretKey key = colon < 0 ? null : keys.get(rest.substring(0, colon));
        if (key == null) {
            throw new IllegalArgumentException("evidence sealed with an unknown key");
        }
        byte[] data = Base64.getDecoder().decode(rest.substring(colon + 1));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("evidence could not be decrypted (tampered or wrong key)", e);
        }
    }
}
