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
import java.util.function.Function;

/**
 * A provider's API for integration tests: each test scripts the answer for a path and can see every
 * request that reached it (to prove Aegis did, or did not, call the provider).
 */
public final class FakeProvider {

    public record Request(String method, String path, Map<String, List<String>> headers, String body) {}

    public record Answer(int status, String body) {}

    private static final HttpServer SERVER = start();
    private static final Map<String, Function<Request, Answer>> ROUTES = new ConcurrentHashMap<>();
    private static final List<Request> RECEIVED = new CopyOnWriteArrayList<>();

    private FakeProvider() {}

    public static URI url() {
        return URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    /** Answers {@code "METHOD /path"} (path prefix match) with {@code answer}. */
    public static void on(String route, Function<Request, Answer> answer) {
        ROUTES.put(route, answer);
    }

    public static List<Request> received(String pathPrefix) {
        return RECEIVED.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                Request request = new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                RECEIVED.add(request);
                Answer answer = ROUTES.entrySet().stream()
                        .filter(e -> (request.method() + " " + request.path()).startsWith(e.getKey()))
                        .map(e -> e.getValue().apply(request))
                        .findFirst()
                        .orElse(new Answer(404, "{\"error\":\"no route\"}"));
                byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(answer.status(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
