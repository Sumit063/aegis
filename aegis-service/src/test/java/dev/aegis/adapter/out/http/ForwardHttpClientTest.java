package dev.aegis.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.crypto.RSADecrypter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ForwardHttpClientTest {

    /** D8: with a JWE key configured, only the application can read the forwarded message. */
    @Test
    void encryptsToTheApplicationsKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());

        String jwe = ForwardHttpClient.encrypt("{\"verdict\":\"PASSED\"}", publicKey);

        assertThat(jwe).doesNotContain("PASSED");
        JWEObject parsed = JWEObject.parse(jwe);
        parsed.decrypt(new RSADecrypter(keys.getPrivate()));
        assertThat(parsed.getPayload().toString()).isEqualTo("{\"verdict\":\"PASSED\"}");
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("RSA-OAEP-256");
    }
}
