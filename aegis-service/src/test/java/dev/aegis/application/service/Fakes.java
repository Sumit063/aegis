package dev.aegis.application.service;

import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressLogPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.MetricsPort;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Verdict;
import dev.aegis.provider.spi.Connection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** In-memory stand-ins for outbound ports, so services are tested without infrastructure. */
final class Fakes {

    private Fakes() {}

    static DirectoryPort directory(List<Connection> connections, List<Application> applications) {
        return new DirectoryPort() {
            @Override
            public Optional<Connection> connection(String name) {
                return connections.stream().filter(c -> c.name().equals(name)).findFirst();
            }

            @Override
            public Optional<Application> application(String name) {
                return applications.stream().filter(a -> a.name().equals(name)).findFirst();
            }

            @Override
            public Optional<Application> applicationByKey(String apiKey) {
                return applications.stream()
                        .filter(a -> a.apiKey().matches(apiKey))
                        .findFirst();
            }

            @Override
            public Collection<Connection> connections() {
                return connections;
            }
        };
    }

    /** Reversible, visibly "sealed" text: enough to prove a body never travels in clear. */
    static final EvidenceCipherPort CIPHER = new EvidenceCipherPort() {
        @Override
        public String seal(byte[] plain) {
            return "sealed:" + new String(plain, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] open(String sealed) {
            return sealed.substring("sealed:".length()).getBytes(StandardCharsets.UTF_8);
        }
    };

    /** Metrics that go nowhere. */
    static final MetricsPort METRICS = new MetricsPort() {
        @Override
        public void verdict(String provider, MessageType type, Verdict verdict, boolean repeat) {}

        @Override
        public void acknowledged(String provider, boolean verified, Duration took) {}

        @Override
        public void forwarded(String application, boolean delivered, boolean gaveUp) {}
    };

    static final class IngressLog implements IngressLogPort {

        final List<IngressRecord> records = new ArrayList<>();
        boolean down;

        @Override
        public void append(IngressRecord record) {
            if (down) {
                throw new IllegalStateException("broker down");
            }
            records.add(record);
        }
    }
}
