package dev.aegis.lab.gateway;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Merchant accounts by name, as configured in a Razorpay dashboard: API keys, webhook secret and URL.
 * A map (not a list) so an environment can override one field of one account.
 */
@ConfigurationProperties("mock")
public record GatewayProperties(Map<String, Account> accounts) {

    public GatewayProperties {
        accounts = Map.copyOf(accounts);
    }

    public record Account(String keyId, String keySecret, String webhookSecret, URI webhookUrl) {}

    public Optional<Account> account(String keyId) {
        return accounts.values().stream().filter(a -> a.keyId().equals(keyId)).findFirst();
    }
}
