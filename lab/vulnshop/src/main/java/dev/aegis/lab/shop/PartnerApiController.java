package dev.aegis.lab.shop;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A recharge API offered to partners (M4 lab). Every request that gets through is executed: the
 * shop debits nothing and remembers nothing, so the lab can count exactly how often each request ran.
 * Direct mode trusts a static key and the body; behind Aegis it runs only what Aegis signed
 * ({@code X-Aegis-Signature} over {@code timestamp.partner.request-id.METHOD.path.body}, LLD §14).
 */
@RestController
class PartnerApiController {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final PartnerApiProperties properties;
    private final JsonMapper json;
    private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
    private final AtomicLong rechargedPaise = new AtomicLong();

    PartnerApiController(PartnerApiProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @PostMapping("/partner-api/recharges")
    ResponseEntity<String> recharge(@RequestBody String body, HttpServletRequest request) {
        if (!authorized(request, body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"unauthorized\"}");
        }
        JsonNode recharge = json.readTree(body);
        long amount = recharge.path("amount").asLong();
        String id = requestId(request);
        int n = executions.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        rechargedPaise.addAndGet(amount);
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"recharge\":\"%s\",\"mobile\":\"%s\",\"amount\":%d,\"execution\":%d}"
                        .formatted(id, recharge.path("mobile").asString(), amount, n));
    }

    @GetMapping("/partner-api/status/{id}")
    ResponseEntity<String> status(@PathVariable String id, HttpServletRequest request) {
        if (!authorized(request, "")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"unauthorized\"}");
        }
        AtomicInteger n = executions.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"recharge\":\"%s\",\"executions\":%d}".formatted(id, n == null ? 0 : n.get()));
    }

    /** Lab only: how often each request ran, and the total recharged. */
    @GetMapping("/lab/partner-executions")
    Map<String, Object> executions() {
        Map<String, Integer> byRequest = new ConcurrentHashMap<>();
        executions.forEach((id, n) -> byRequest.put(id, n.get()));
        return Map.of("byRequest", byRequest, "rechargedPaise", rechargedPaise.get());
    }

    private boolean authorized(HttpServletRequest request, String body) {
        if (!properties.behindAegis()) {
            String key = request.getHeader("X-Api-Key");
            return key != null && key.equals(properties.apiKey());
        }
        String timestamp = request.getHeader("X-Aegis-Timestamp");
        String signature = request.getHeader("X-Aegis-Signature");
        if (timestamp == null || signature == null || !signature.startsWith("v1=")) {
            return false;
        }
        try {
            Instant sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(sentAt, Instant.now()).abs().compareTo(MAX_AGE) > 0) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String query = request.getQueryString();
        String path = request.getRequestURI().substring("/partner-api".length()) + (query == null ? "" : "?" + query);
        String signed = String.join(
                        ".",
                        timestamp,
                        String.valueOf(request.getHeader("X-Aegis-Partner")),
                        String.valueOf(request.getHeader("X-Aegis-Request-Id")),
                        request.getMethod(),
                        path)
                + "." + body;
        return Hmac.matches(properties.aegisSecret(), signed, signature.substring(3));
    }

    private static String requestId(HttpServletRequest request) {
        String aegis = request.getHeader("X-Aegis-Request-Id");
        String direct = request.getHeader("X-Request-Id");
        return aegis != null ? aegis : direct != null ? direct : "none";
    }
}
