package dev.aegis.lab.gateway;

import dev.aegis.lab.gateway.GatewayProperties.Account;
import dev.aegis.lab.gateway.Model.Order;
import dev.aegis.lab.gateway.Model.Payment;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The gateway's own records: the truth about every order and payment, per merchant account. */
@Component
class Ledger {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    Order createOrder(Account account, long amount, String currency, String receipt, Map<String, String> notes) {
        if (amount < 100) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "The amount must be atleast INR 1.00");
        }
        Order order = new Order(
                id("order_"),
                "order",
                amount,
                0,
                amount,
                currency == null ? "INR" : currency,
                receipt,
                "created",
                0,
                notes == null ? Map.of() : Map.copyOf(notes),
                Instant.now().getEpochSecond());
        orders.put(order.id(), order);
        owners.put(order.id(), account.keyId());
        return order;
    }

    Order order(Account account, String id) {
        return owned(account, id, orders);
    }

    Payment payment(Account account, String id) {
        return owned(account, id, payments);
    }

    List<Payment> paymentsOf(Account account, String orderId) {
        order(account, orderId);
        return payments.values().stream()
                .filter(p -> orderId.equals(p.orderId()))
                .sorted((a, b) -> Long.compare(a.createdAt(), b.createdAt()))
                .toList();
    }

    /**
     * The customer pays. Like Razorpay, a payment against an order must be for the order's exact
     * amount; a payment without an order (checkout opened with only the public key) is allowed.
     */
    synchronized Payment pay(
            Account account,
            String orderId,
            Long amount,
            String currency,
            Map<String, String> notes,
            boolean captured,
            long createdAt) {
        long value;
        String cur;
        if (orderId != null) {
            Order order = order(account, orderId);
            if (amount != null && amount != order.amount()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "Amount does not match the order amount");
            }
            if ("paid".equals(order.status())) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "Order is already paid");
            }
            orders.put(orderId, order.attempted(captured));
            value = order.amount();
            cur = order.currency();
        } else {
            if (amount == null || amount < 100) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "The amount must be atleast INR 1.00");
            }
            value = amount;
            cur = currency == null ? "INR" : currency;
        }
        Payment payment = new Payment(
                id("pay_"),
                "payment",
                value,
                cur,
                captured ? "captured" : "failed",
                orderId,
                "upi",
                notes == null ? Map.of() : Map.copyOf(notes),
                createdAt);
        payments.put(payment.id(), payment);
        owners.put(payment.id(), account.keyId());
        return payment;
    }

    private <T> T owned(Account account, String id, Map<String, T> store) {
        T value = store.get(id);
        if (value == null || !account.keyId().equals(owners.get(id))) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "The id provided does not exist");
        }
        return value;
    }

    static String id(String prefix) {
        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < 14; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }
}
