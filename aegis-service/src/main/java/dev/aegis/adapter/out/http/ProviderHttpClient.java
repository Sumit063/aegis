package dev.aegis.adapter.out.http;

import dev.aegis.application.port.out.ProviderClientPort;
import dev.aegis.provider.spi.Destinations;
import dev.aegis.provider.spi.PreparedCall;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Calls providers with the JDK HTTP client: connect within 3 s, answer within 10 s. Requests run on
 * the caller's virtual thread, so a slow provider costs no platform thread. No retries: an
 * application call that fails is the application's to repeat (HLD §9).
 */
@Component
class ProviderHttpClient implements ProviderClientPort {

    // Headers the JDK client sets itself and refuses from callers.
    private static final Set<String> RESTRICTED = Set.of("content-length", "host", "connection", "expect", "upgrade");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Response send(PreparedCall call) {
        // Re-checked at send time: the name may resolve differently than at startup (DNS rebinding).
        Destinations.check(call.uri(), true);
        HttpRequest.Builder request = HttpRequest.newBuilder(call.uri())
                .timeout(Duration.ofSeconds(10))
                .method(
                        call.method(),
                        call.body().length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(call.body()));
        call.headers().forEach((name, value) -> {
            if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                request.header(name, value);
            }
        });
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "provider unreachable: " + call.uri().getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling the provider", e);
        }
    }
}
