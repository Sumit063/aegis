package dev.aegis.adapter.out.metrics;

import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Verdict;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * {@code aegis_messages_total{provider,type,verdict,reason}}, {@code aegis_ingress_ack_seconds},
 * {@code aegis_forward_total{application,result}}, {@code aegis_partner_requests_total{partner,operation,result}},
 * {@code aegis_partner_upstream_seconds{partner}}. Tag values come from fixed sets (no references),
 * so cardinality stays small.
 */
@Component
class MicrometerMetrics implements MetricsPort {

    private final MeterRegistry registry;

    MicrometerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void verdict(String provider, MessageType type, Verdict verdict, boolean repeat) {
        registry.counter(
                        "aegis.messages",
                        "provider",
                        provider,
                        "type",
                        type.name(),
                        "verdict",
                        repeat ? "REPEAT" : verdict.passed() ? "PASSED" : "BLOCKED",
                        "reason",
                        repeat || verdict.passed() ? "none" : verdict.reason().name())
                .increment();
    }

    @Override
    public void acknowledged(String provider, boolean verified, Duration took) {
        Timer.builder("aegis.ingress.ack")
                .description("time to answer a provider, signature check and durable write included")
                .tag("provider", provider)
                .tag("verified", String.valueOf(verified))
                .publishPercentiles(0.5, 0.99)
                // Buckets too, so p99 can be computed across instances (alert SlowAcknowledgement).
                .publishPercentileHistogram()
                .register(registry)
                .record(took);
    }

    @Override
    public void forwarded(String application, boolean delivered, boolean gaveUp) {
        registry.counter(
                        "aegis.forward",
                        "application",
                        application,
                        "result",
                        delivered ? "delivered" : gaveUp ? "failed" : "retry")
                .increment();
    }

    @Override
    public void partner(String partner, String operation, String result) {
        registry.counter("aegis.partner.requests", "partner", partner, "operation", operation, "result", result)
                .increment();
    }

    @Override
    public void partnerUpstream(String partner, Duration took) {
        Timer.builder("aegis.partner.upstream")
                .description("time the application took to answer a partner request")
                .tag("partner", partner)
                .publishPercentileHistogram()
                .register(registry)
                .record(took);
    }
}
