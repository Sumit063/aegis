package dev.aegis.lab.gateway;

import java.util.Map;

/** Razorpay entities as the API returns them (snake_case on the wire). */
final class Model {

    private Model() {}

    record Order(
            String id,
            String entity,
            long amount,
            long amountPaid,
            long amountDue,
            String currency,
            String receipt,
            String status,
            int attempts,
            Map<String, String> notes,
            long createdAt) {

        Order attempted(boolean paid) {
            return new Order(
                    id,
                    entity,
                    amount,
                    paid ? amount : amountPaid,
                    paid ? 0 : amountDue,
                    currency,
                    receipt,
                    paid ? "paid" : "attempted",
                    attempts + 1,
                    notes,
                    createdAt);
        }
    }

    record Payment(
            String id,
            String entity,
            long amount,
            String currency,
            String status,
            String orderId,
            String method,
            Map<String, String> notes,
            long createdAt) {}

    /** A delivered webhook: the exact signed body, kept so it can be re-delivered or "leaked". */
    record Event(String id, String keyId, String body, String signature) {}
}
