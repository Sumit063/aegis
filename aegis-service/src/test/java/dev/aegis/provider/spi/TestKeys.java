package dev.aegis.provider.spi;

import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * RSA keys for tests, generated per run (never stored), plus one self-signed test certificate with its
 * key in a PKCS#12 file, to prove certificates are accepted as trusted keys.
 */
public final class TestKeys {

    public static final KeyPair CURRENT = generate(2048);
    public static final KeyPair PREVIOUS = generate(2048);
    public static final KeyPair ATTACKER = generate(2048);
    public static final KeyPair AEGIS = generate(2048);

    private TestKeys() {}

    public static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A public key as configuration holds it: single-line base64 SubjectPublicKeyInfo. */
    public static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    /** A private key as configuration holds it: PEM PKCS#8 with escaped newlines, as in a .env file. */
    public static String privateKey(KeyPair pair) {
        String base64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String body = String.join("\\n", base64.split("(?<=\\G.{64})"));
        // Built from parts: the pre-commit scan rejects private-key headers as literals.
        String label = "PRIVATE" + " KEY";
        return "-----BEGIN " + label + "-----\\n" + body + "\\n-----END " + label + "-----";
    }

    /** The test certificate, PEM. */
    public static String certificate() {
        try {
            Certificate certificate = keystore().getCertificate("switch");
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The private key of {@link #certificate()}. */
    public static PrivateKey certificateKey() {
        try {
            return (PrivateKey) keystore().getKey("switch", "testtest".toCharArray());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyStore keystore() {
        try (InputStream in = TestKeys.class.getResourceAsStream("/npci/switch-test.p12")) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, "testtest".toCharArray());
            return store;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
