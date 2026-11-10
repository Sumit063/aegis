package dev.aegis.application.port.out;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Verdict;
import java.time.Duration;

/** What operators measure and alert on (LLD §12). References and counts only, never content. */
public interface MetricsPort {

    /** A message got a verdict ({@code repeat}: it was a re-delivery, only counted). */
    void verdict(String provider, MessageType type, Verdict verdict, boolean repeat);

    /** Ingress answered a provider. */
    void acknowledged(String provider, boolean verified, Duration took);

    /** One delivery attempt to an application. {@code gaveUp}: the message is now FAILED. */
    void forwarded(String application, boolean delivered, boolean gaveUp);

    /**
     * A partner request was answered, replayed or refused.
     *
     * @param partner a configured partner's name, or {@code unknown}
     * @param operation a granted operation, or {@code other}
     * @param result {@code ANSWERED}, {@code REPLAYED} or a refusal
     */
    void partner(String partner, String operation, String result);

    /** How long an application took to answer a partner request. */
    void partnerUpstream(String partner, Duration took);
}
