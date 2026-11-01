package dev.aegis.lab.shop;

import java.time.Instant;

/**
 * One order. {@code fulfilments} counts how many times goods were handed over (or a wallet credited):
 * anything above one, or any fulfilment of an unpaid order, is a loss.
 */
public record ShopOrder(
        String id, long amount, String gatewayOrderId, Status status, int fulfilments, Instant fulfilledAt) {

    public enum Status {
        CREATED,
        PAID,
        CANCELLED
    }

    ShopOrder fulfilled() {
        return new ShopOrder(
                id,
                amount,
                gatewayOrderId,
                Status.PAID,
                fulfilments + 1,
                fulfilledAt == null ? Instant.now() : fulfilledAt);
    }

    ShopOrder cancelled() {
        return new ShopOrder(id, amount, gatewayOrderId, Status.CANCELLED, fulfilments, fulfilledAt);
    }
}
