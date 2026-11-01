package dev.aegis;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The protected application in integration tests: receives forwards; can be made to fail (down). */
public final class FakeApplication {

    public record Received(String timestamp, String signature, String contentType, String body) {}

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final HttpServer SERVER = start();

    private FakeApplication() {}

    public static URI url() {
        return URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/aegis/messages");
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
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
