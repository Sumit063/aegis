package dev.aegis.provider.example;

import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Test fixtures for the example provider: a connection and genuinely signed messages. */
public final class Example {

    public static final String WEBHOOK_SECRET = "example-webhook-secret-current-001";
    public static final String API_KEY = "example-api-key-for-tests-0000001";

    private Example() {}

    public static Connection connection(URI baseUrl) {
        return new Connection(
                "example",
                "example",
                "shop",
                baseUrl,
                Map.of(),
                new SecretSet(Map.of(
                        "webhook", List.of(new Secret(WEBHOOK_SECRET)), "api-key", List.of(new Secret(API_KEY)))));
    }

    public static Connection connection() {
        return connection(URI.create("http://provider.test"));
    }

    public static RawMessage signed(String body) {
        return RawMessage.of(
                Map.of(
                        ExampleAdapter.SIGNATURE,
                        ExampleAdapter.sign(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8))),
                body);
    }
}
