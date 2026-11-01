package dev.aegis.application.port.out;

import dev.aegis.domain.shared.Money;
import java.util.Map;

/**
 * Delivers a verified message to an application (LLD §8): JSON, signed with the application's secret
 * ({@code X-Aegis-Signature}), optionally encrypted to its public key, over HTTPS (mTLS at the proxy).
 */
public interface ForwardClientPort {

    /** @return null if the application acknowledged (2xx), otherwise why not */
    String deliver(Application application, Delivery delivery);

    /**
     * What the application receives: the facts Aegis checked, and the provider's original message,
     * unchanged, so the application can re-verify it (zero trust in Aegis too).
     *
     * @param id stable across retries ({@code <payment id>-<entry number>}), so repeats are ignorable
     */
    record Delivery(
            String id,
            String verdict,
            String provider,
            String type,
            String outcome,
            String merchantRef,
            String providerRef,
            String paymentRef,
            Money amount,
            Map<String, String> originalHeaders,
            byte[] originalBody) {}
}
