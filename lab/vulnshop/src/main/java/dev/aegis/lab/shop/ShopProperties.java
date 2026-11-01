package dev.aegis.lab.shop;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ordersUrl where orders are created: the gateway's {@code /v1/orders}, or Aegis's
 *     {@code /gw/<connection>/validations}
 * @param authorization the {@code Authorization} header for that call: the gateway's Basic API key,
 *     or the shop's Aegis application key
 * @param keySecret Checkout signature key (direct mode only)
 * @param webhookSecret webhook signature key (direct mode only)
 * @param aegisSecret key Aegis signs forwarded messages with (Aegis mode only)
 * @param flaws flaws switched on at start (see {@link Flaws})
 * @param memory how long webhook event IDs are remembered, and the oldest webhook accepted
 */
@ConfigurationProperties("shop")
public record ShopProperties(
        URI ordersUrl,
        String authorization,
        String keySecret,
        String webhookSecret,
        String aegisSecret,
        Set<Flaw> flaws,
        Duration memory) {

    public ShopProperties {
        flaws = flaws == null || flaws.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flaws));
        memory = memory == null ? Duration.ofDays(7) : memory;
    }
}
