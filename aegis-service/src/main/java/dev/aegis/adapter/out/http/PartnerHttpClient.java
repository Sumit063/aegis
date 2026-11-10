package dev.aegis.adapter.out.http;

import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.PartnerClientPort;
import dev.aegis.provider.spi.Destinations;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Calls an application's partner API (LLD §14): {@code partner-url} + the partner's path, the body
 * as sent, and {@code X-Aegis-Partner}, {@code X-Aegis-Request-Id}, {@code X-Aegis-Timestamp},
 * {@code X-Aegis-Signature: v1=hex(HMAC_SHA256(secret, timestamp.partner.request-id.METHOD.path.body))}.
 * The partner's own credentials are not passed on. 3 s to connect, 10 s to answer, no redirects.
 */
@Component
class PartnerHttpClient implements PartnerClientPort {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Clock clock;

    PartnerHttpClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Response send(Application application, Request request) {
        URI uri;
        try {
            String base = application.partnerUrl().toString().replaceAll("/+$", "");
            uri = URI.create(base + request.path());
            // Re-checked at send time: the name may resolve differently than at startup (DNS rebinding).
            Destinations.check(uri, true);
        } catch (IllegalArgumentException e) {
            throw new NotSentException("refused destination: " + e.getMessage(), e);
        }
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .method(
                        request.method(),
                        request.body().length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.body()))
                .header("X-Aegis-Partner", request.partner())
                .header("X-Aegis-Request-Id", request.requestId())
                .header("X-Aegis-Timestamp", timestamp)
                .header(
                        "X-Aegis-Signature",
                        "v1=" + application.signingSecret().hmacSha256Hex(signed(timestamp, request)));
        if (request.contentType() != null) {
            builder.header("Content-Type", request.contentType());
        }
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.body());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new NotSentException("application unreachable: " + uri.getHost(), e);
        } catch (IOException e) {
            throw new NoAnswerException("no answer from " + uri.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoAnswerException("interrupted calling " + uri.getHost(), e);
        }
    }

    static byte[] signed(String timestamp, Request request) {
        String head =
                String.join(".", timestamp, request.partner(), request.requestId(), request.method(), request.path())
                        + ".";
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        data.writeBytes(request.body());
        return data.toByteArray();
    }
}
