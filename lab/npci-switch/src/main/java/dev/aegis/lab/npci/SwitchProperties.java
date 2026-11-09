package dev.aegis.lab.npci;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The switch's own RSA key (PKCS#8, base64), Aegis's public key (to verify requests), where results
 * are delivered, how long after the request, and for how long an unacknowledged result is redelivered
 * (once a second; zero sends each result once).
 */
@ConfigurationProperties("npci")
public record SwitchProperties(
        String switchKey, String aegisKey, URI bbpsCallback, URI upiCallback, Duration delay, Duration redeliver) {

    public SwitchProperties {
        delay = delay == null ? Duration.ofMillis(200) : delay;
        redeliver = redeliver == null ? Duration.ZERO : redeliver;
    }
}
