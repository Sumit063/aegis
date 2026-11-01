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
}
