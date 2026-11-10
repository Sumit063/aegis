package dev.aegis;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The protected application in integration tests: receives forwards; can be made to fail (down).
 * Its partner API ({@link #partnerUrl()}) executes whatever reaches it and counts executions per
 * request ID, so tests can prove a request ran once.
 */
public final class FakeApplication {

    public record Received(String timestamp, String signature, String contentType, String body) {}

    /** One call to the partner API as it arrived from Aegis. */
    public record PartnerCall(
            String method,
            String path,
            String partner,
            String requestId,
            String timestamp,
            String signature,
            String contentType,
            boolean partnerCredentialsPassedOn,
            byte[] body) {}

    private static final List<PartnerCall> PARTNER_CALLS = new CopyOnWriteArrayList<>();
    private static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger PARTNER_STATUS = new AtomicInteger(201);
    private static final AtomicLong PARTNER_DELAY_MS = new AtomicLong();

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final HttpServer SERVER = start();

    private FakeApplication() {}

    public static URI url() {
        return URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/aegis/messages");
    }

    public static URI partnerUrl() {
        return URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/partner-api");
    }

    public static List<PartnerCall> partnerCalls() {
        return PARTNER_CALLS;
    }

    /** How many times the partner API executed {@code requestId}. */
    public static int executions(String requestId) {
        AtomicInteger n = EXECUTIONS.get(requestId);
        return n == null ? 0 : n.get();
    }

    /** Answer partner calls with {@code status}, after {@code delayMs}. */
    public static void partnerAnswer(int status, long delayMs) {
        PARTNER_STATUS.set(status);
        PARTNER_DELAY_MS.set(delayMs);
    }

    /** Every delivery attempt, including refused ones. */
    public static List<Received> received() {
        return RECEIVED;
    }

    /** Answer every delivery with {@code status} (e.g. 503 to simulate the application being down). */
    public static void answer(int status) {
        STATUS.set(status);
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/aegis/messages", exchange -> {
                RECEIVED.add(new Received(
                        exchange.getRequestHeaders().getFirst("X-Aegis-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Aegis-Signature"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(STATUS.get(), -1);
                exchange.close();
            });
            server.createContext("/partner-api", exchange -> {
                String requestId = exchange.getRequestHeaders().getFirst("X-Aegis-Request-Id");
                byte[] body = exchange.getRequestBody().readAllBytes();
                String query = exchange.getRequestURI().getRawQuery();
                PARTNER_CALLS.add(new PartnerCall(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getRawPath().substring("/partner-api".length())
                                + (query == null ? "" : "?" + query),
                        exchange.getRequestHeaders().getFirst("X-Aegis-Partner"),
                        requestId,
                        exchange.getRequestHeaders().getFirst("X-Aegis-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Aegis-Signature"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().containsKey("X-Signature")
                                || exchange.getRequestHeaders().containsKey("X-Partner-Id"),
                        body));
                int n = EXECUTIONS
                        .computeIfAbsent(String.valueOf(requestId), k -> new AtomicInteger())
                        .incrementAndGet();
                try {
                    Thread.sleep(PARTNER_DELAY_MS.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] answer =
                        ("{\"executed\":\"" + requestId + "\",\"n\":" + n + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(PARTNER_STATUS.get(), answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
