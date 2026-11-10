package dev.aegis.application.port.in;

import java.time.Duration;

/**
 * A partner's call to an application's partner API (LLD §14): authenticated, fresh, granted, within
 * its rate, and executed at most once per request ID. The only way a partner reaches the application.
 */
public interface PartnerUseCase {

    /**
     * The request as it arrived. Header values are as sent (possibly missing or malformed).
     *
     * @param path below {@code /partner}, with the query (e.g. {@code /recharges?x=1})
     * @param certificateSubject the client certificate's subject, or null
     */
    record Call(
            String partner,
            String requestId,
            String timestamp,
            String signature,
            String certificateSubject,
            String method,
            String path,
            String contentType,
            byte[] body) {}

    /**
     * The application's answer as it came, or Aegis's refusal.
     *
     * @param replayed this is the stored answer of an earlier execution of the same request ID
     * @param refusal why Aegis answered instead of the application, or null
     * @param retryAfter when to retry a refusal that may pass later, or null
     */
    record Answer(int status, String contentType, byte[] body, boolean replayed, String refusal, Duration retryAfter) {}

    Answer call(Call call);
}
