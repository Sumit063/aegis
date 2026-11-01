package dev.aegis.application.port.out;

import dev.aegis.provider.spi.Secret;
import java.net.URI;
import java.util.Objects;

/**
 * A system Aegis protects (LLD §11): how it authenticates to Aegis, and where and how Aegis delivers
 * verified messages to it.
 *
 * @param clientCertSubject required client-certificate subject on the Gateway API, or null
 * @param returnUrl where the customer's browser goes after a Checkout callback, or null
 * @param jwePublicKey the application's RSA public key (base64 X.509) to encrypt forwards, or null
 */
public record Application(
        String name,
        Secret apiKey,
        URI forwardUrl,
        URI returnUrl,
        String clientCertSubject,
        Secret signingSecret,
        String jwePublicKey) {

    public Application {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(forwardUrl, "forwardUrl");
        Objects.requireNonNull(signingSecret, "signingSecret");
    }
}
