package dev.aegis.config;

import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.Partner;
import dev.aegis.application.port.out.RateLimiterPort.Rate;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** {@link DirectoryPort} over the validated configuration; built once at startup. */
@Component
class ConfiguredDirectory implements DirectoryPort {

    private final Map<String, Connection> connections;
    private final Map<String, Application> applications;
    private final Map<String, Partner> partners;

    ConfiguredDirectory(AegisProperties properties) {
        applications = properties.applications().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> application(e.getKey(), e.getValue())));
        connections = properties.connections().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> connection(e.getKey(), e.getValue())));
        partners = properties.partners().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> partner(e.getKey(), e.getValue())));
    }

    @Override
    public Optional<Connection> connection(String name) {
        return Optional.ofNullable(connections.get(name));
    }

    @Override
    public Optional<Application> application(String name) {
        return Optional.ofNullable(applications.get(name));
    }

    @Override
    public Optional<Application> applicationByKey(String apiKey) {
        // Compare against every key so the time taken does not reveal which one matched.
        Application found = null;
        for (Application app : applications.values()) {
            if (app.apiKey().matches(apiKey)) {
                found = app;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public Collection<Connection> connections() {
        return connections.values();
    }

    @Override
    public Optional<Partner> partner(String name) {
        return Optional.ofNullable(partners.get(name));
    }

    private static Partner partner(String name, AegisProperties.PartnerProperties p) {
        return new Partner(
                name,
                p.application(),
                p.secrets().stream().map(Secret::new).toList(),
                blankToNull(p.clientCertSubject()),
                new Rate(p.requestsPerMinute(), p.burst()),
                p.operations().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                e -> new Partner.Grant(
                                        Set.copyOf(e.getValue().methods()),
                                        e.getValue().requestsPerMinute() == null
                                                ? null
                                                : new Rate(
                                                        e.getValue().requestsPerMinute(),
                                                        e.getValue().burst())))));
    }

    private static Connection connection(String name, AegisProperties.ConnectionProperties p) {
        SecretSet secrets = new SecretSet(p.secrets().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream().map(Secret::new).toList())));
        return new Connection(name, p.adapter(), p.application(), p.baseUrl(), p.settings(), secrets);
    }

    private static Application application(String name, AegisProperties.ApplicationProperties p) {
        return new Application(
                name,
                new Secret(p.apiKey()),
                p.forwardUrl(),
                p.returnUrl(),
                blankToNull(p.clientCertSubject()),
                new Secret(p.signingSecret()),
                blankToNull(p.jwePublicKey()),
                p.partnerUrl());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
