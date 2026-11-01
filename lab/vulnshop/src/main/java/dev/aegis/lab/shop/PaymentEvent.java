package dev.aegis.lab.shop;

import java.time.Instant;

/**
 * What the shop learned about a payment, whichever way it arrived (direct from the gateway, or
 * forwarded by Aegis).
 *
 * @param channel callback (browser) or webhook (server)
 * @param eventId webhook event ID; null for callbacks
 * @param orderHint the shop order the sender names: {@code notes.shop_order} or the callback URL
 *     directly, Aegis's {@code merchantRef} behind Aegis
 * @param amount null when the message carries none (Checkout callback)
 * @param createdAt null when the message carries none (Checkout callback)
 */
public record PaymentEvent(
        Channel channel,
        String eventId,
        String paymentId,
        String gatewayOrderId,
        String orderHint,
        Long amount,
        boolean captured,
        Instant createdAt) {

    public enum Channel {
        CALLBACK,
        WEBHOOK
    }
}
