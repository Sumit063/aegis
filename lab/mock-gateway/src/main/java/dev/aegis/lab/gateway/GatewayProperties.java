package dev.aegis.lab.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Merchant accounts by name, as configured in a Razorpay dashboard: API keys, webhook secret and URL.
 * A map (not a list) so an environment can override one field of one account.
 *
 * <p>{@code webhookRetry}: how long a webhook the merchant did not acknowledge (non-2xx or
 * unreachable) is retried, once a second, as Razorpay retries for 24 hours. Zero (the default) sends
 * each webhook once, so every attack sees exactly the deliveries it asked for.
 */
@ConfigurationProperties("mock")
public record GatewayProperties(Map<String, Account> accounts, Duration webhookRetry) {

    public GatewayProperties {
        accounts = Map.copyOf(accounts);
        webhookRetry = webhookRetry == null ? Duration.ZERO : webhookRetry;
    }

    public record Account(String keyId, String keySecret, String webhookSecret, URI webhookUrl) {}

    public Optional<Account> account(String keyId) {
        return accounts.values().stream().filter(a -> a.keyId().equals(keyId)).findFirst();
    }
}
