package dev.aegis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operators' key for {@code /admin/**} ({@code aegis.admin.api-key}). Generated per environment
 * by {@code deploy.sh}; at least 32 characters; never printed.
 */
@ConfigurationProperties("aegis.admin")
public record AdminProperties(String apiKey) {

    public AdminProperties {
        if (apiKey == null || apiKey.strip().length() < 32) {
            throw new IllegalStateException(
                    "aegis.admin.api-key must be at least 32 characters (set AEGIS_ADMIN_API_KEY)");
        }
    }

    @Override
    public String toString() {
        return "AdminProperties[apiKey=****]";
    }
}
