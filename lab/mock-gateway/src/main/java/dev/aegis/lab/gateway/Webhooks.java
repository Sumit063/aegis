package dev.aegis.lab.gateway;

import dev.aegis.lab.gateway.GatewayProperties.Account;
import dev.aegis.lab.gateway.Model.Event;
import dev.aegis.lab.gateway.Model.Payment;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds, signs and delivers webhooks the way Razorpay does: the body is signed with the account's
 * webhook secret ({@code X-Razorpay-Signature}) and carries an event ID ({@code X-Razorpay-Event-Id}).
 * Every event is kept, so it can be re-delivered (a provider retry) or replayed (an attacker).
 */
@Component
class Webhooks {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Event> events = new ConcurrentHashMap<>();
    private final JsonMapper json;

    Webhooks(JsonMapper json) {
        this.json = json;
    }

    Event create(Account account, Payment payment) {
        String name = "captured".equals(payment.status()) ? "payment.captured" : "payment.failed";
        String body = json.writeValueAsString(Map.of(
                "entity",
                "event",
                "account_id",
                "acc_lab",
                "event",
                name,
                "contains",
                List.of("payment"),
                "payload",
                Map.of("payment", Map.of("entity", payment)),
                "created_at",
                payment.createdAt()));
        Event event =
                new Event(Ledger.id("evt_"), account.keyId(), body, Hmac.sha256Hex(account.webhookSecret(), body));
        events.put(event.id(), event);
        return event;
    }

    Optional<Event> find(String id) {
        return Optional.ofNullable(events.get(id));
    }

    /** Delivers an event and returns the receiver's HTTP status, or 0 if it could not be reached. */
    int deliver(Account account, Event event) {
        HttpRequest request = HttpRequest.newBuilder(account.webhookUrl())
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", event.signature())
                .header("X-Razorpay-Event-Id", event.id())
                .POST(HttpRequest.BodyPublishers.ofString(event.body()))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
