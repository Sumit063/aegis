package dev.aegis.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSAEncrypter;
import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.ForwardClientPort;
import dev.aegis.provider.spi.Destinations;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers to applications (LLD §8): the JSON message, optionally as a JWE (RSA-OAEP-256, A256GCM)
 * to the application's public key, signed with {@code X-Aegis-Signature: v1=hex(HMAC_SHA256(secret,
 * timestamp + "." + body))} and {@code X-Aegis-Timestamp}. 3 s to connect, 10 s to answer.
 */
@Component
class ForwardHttpClient implements ForwardClientPort {

    /** The wire format; amounts in minor units, the original body base64. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(
            String id,
            String verdict,
            String provider,
            String type,
            String outcome,
            String merchantRef,
            String providerRef,
            String paymentRef,
            Long amount,
            String currency,
            Original original) {}

    record Original(Map<String, String> headers, String body) {}

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json;
    private final Clock clock;

    ForwardHttpClient(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String deliver(Application application, Delivery delivery) {
        try {
            Destinations.check(application.forwardUrl(), true);
        } catch (IllegalArgumentException e) {
            return "refused destination: " + e.getMessage();
        }
        String body = json.writeValueAsString(message(delivery));
        String contentType = "application/json";
        if (application.jwePublicKey() != null) {
            body = encrypt(body, application.jwePublicKey());
            contentType = "application/jose";
        }
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(application.forwardUrl())
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .header("X-Aegis-Timestamp", timestamp)
                .header("X-Aegis-Message-Id", delivery.id())
                .header("X-Aegis-Signature", "v1=" + sign(application, timestamp + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2 ? null : "HTTP " + status;
        } catch (IOException e) {
            return e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    static Message message(Delivery d) {
        return new Message(
                d.id(),
                d.verdict(),
                d.provider(),
                d.type(),
                d.outcome(),
                d.merchantRef(),
                d.providerRef(),
                d.paymentRef(),
                d.amount() == null ? null : d.amount().minorUnits(),
                d.amount() == null ? null : d.amount().currency().getCurrencyCode(),
                new Original(d.originalHeaders(), Base64.getEncoder().encodeToString(d.originalBody())));
    }

    static String encrypt(String body, String base64PublicKey) {
        try {
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
            JWEObject jwe = new JWEObject(
                    new JWEHeader(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM), new Payload(body));
            jwe.encrypt(new RSAEncrypter(key));
            return jwe.serialize();
        } catch (GeneralSecurityException | JOSEException | IllegalArgumentException e) {
            throw new IllegalStateException("cannot encrypt to the application's key", e);
        }
    }

    private static String sign(Application application, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(application.signingSecret().bytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
