package dev.aegis.lab.shop;

import dev.aegis.lab.shop.PaymentEvent.Channel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The shop's business logic: create orders, and fulfil or cancel them when payment news arrives.
 * Every protection is here, and each flaw switch turns exactly one off.
 */
@Component
public class Shop {

    private final ShopProperties properties;
    private final Map<String, ShopOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, Instant> seenEvents = new ConcurrentHashMap<>();
    private final Map<Channel, Set<String>> fulfilledPayments =
            Map.of(Channel.CALLBACK, ConcurrentHashMap.newKeySet(), Channel.WEBHOOK, ConcurrentHashMap.newKeySet());
    private final AtomicInteger sequence = new AtomicInteger();

    public Shop(ShopProperties properties) {
        this.properties = properties;
    }

    ShopOrder add(long amount, String gatewayOrderId, String id) {
        ShopOrder order = new ShopOrder(id, amount, gatewayOrderId, ShopOrder.Status.CREATED, 0);
        orders.put(id, order);
        return order;
    }

    String nextId() {
        return "SHOP-" + sequence.incrementAndGet();
    }

    Optional<ShopOrder> order(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    /** Applies payment news. Returns what happened, for the response and the lab report. */
    synchronized String apply(PaymentEvent event) {
        Optional<ShopOrder> found = trustsHint(event.channel())
                ? order(event.orderHint())
                : orders.values().stream()
                        .filter(o -> o.gatewayOrderId().equals(event.gatewayOrderId()))
                        .findFirst();
        if (found.isEmpty()) {
            return "ignored: unknown order";
        }
        ShopOrder order = found.get();
        if (!properties.has(Flaw.F3) && event.amount() != null && event.amount() != order.amount()) {
            return "ignored: amount differs";
        }
        if (event.channel() == Channel.WEBHOOK) {
            Instant now = Instant.now();
            seenEvents.values().removeIf(at -> at.isBefore(now.minus(properties.memory())));
            if (!properties.has(Flaw.F10)
                    && event.createdAt() != null
                    && event.createdAt().isBefore(now.minus(properties.memory()))) {
                return "ignored: too old";
            }
            if (!properties.has(Flaw.F5) && seenEvents.putIfAbsent(event.eventId(), now) != null) {
                return "ignored: event already processed";
            }
        }
        if (!event.captured()) {
            if (order.status() == ShopOrder.Status.PAID && !properties.has(Flaw.F6)) {
                return "ignored: order already paid";
            }
            orders.put(order.id(), order.cancelled());
            return "cancelled " + order.id();
        }
        Channel other = event.channel() == Channel.CALLBACK ? Channel.WEBHOOK : Channel.CALLBACK;
        if (!properties.has(Flaw.F9) && fulfilledPayments.get(other).contains(event.paymentId())) {
            return "ignored: payment already fulfilled";
        }
        fulfilledPayments.get(event.channel()).add(event.paymentId());
        orders.put(order.id(), order.fulfilled());
        return "fulfilled " + order.id();
    }

    // F3: the webhook names its own order (notes); F8: the callback URL names the order.
    private boolean trustsHint(Channel channel) {
        return channel == Channel.WEBHOOK ? properties.has(Flaw.F3) : properties.has(Flaw.F8);
    }
}
