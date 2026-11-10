package dev.aegis.lab.shop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shop's partner API (M4 lab).
 *
 * @param mode {@code direct}: partners call the shop itself with a static API key, the way many
 *     partner APIs are built; {@code aegis}: only Aegis calls it, and only what Aegis signed runs
 * @param apiKey the partners' key in direct mode
 * @param aegisSecret the key Aegis signs partner requests with, in aegis mode
 */
@ConfigurationProperties("partner-api")
public record PartnerApiProperties(String mode, String apiKey, String aegisSecret) {

    public PartnerApiProperties {
        mode = mode == null ? "direct" : mode;
    }

    boolean behindAegis() {
        return mode.equals("aegis");
    }
}
