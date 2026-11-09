package dev.aegis.lab.shop;

import java.time.Duration;
import java.time.Instant;

/** How an application checks a message Aegis forwards (LLD §8): HMAC over {@code timestamp.body}, at most 5 minutes old. */
final class AegisSignature {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private AegisSignature() {}

    static boolean verify(String secret, String timestamp, String body, String signature) {
        if (timestamp == null || signature == null || !signature.startsWith("v1=")) {
            return false;
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        boolean recent = Duration.between(Instant.ofEpochSecond(sentAt), Instant.now())
                        .abs()
                        .compareTo(MAX_AGE)
                <= 0;
        return recent && Hmac.matches(secret, timestamp + "." + body, signature.substring(3));
    }
}
