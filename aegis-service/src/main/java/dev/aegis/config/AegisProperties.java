package dev.aegis.config;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root of the {@code aegis.*} configuration (LLD §11). Connections and applications are declared in
 * the environment's {@code aegis.yaml}; secret values come only from environment variables.
 *
 * <p>Fails closed: a missing environment or role, an empty or short secret, or a connection naming
 * an unknown application stops startup (DECISIONS D17). Messages name the setting, never its value.
 */
@ConfigurationProperties(prefix = "aegis")
public record AegisProperties(
        DeploymentEnvironment environment,
        Set<Role> roles,
        Map<String, ConnectionProperties> connections,
        Map<String, ApplicationProperties> applications) {

    /** Provider secrets: Razorpay key secrets are 24 characters, so 16 is the floor. */
    static final int MIN_PROVIDER_SECRET = 16;
    /** Secrets Aegis shares with applications are generated, so they can be long. */
    static final int MIN_APPLICATION_SECRET = 32;

    public AegisProperties {
        Objects.requireNonNull(
                environment, "aegis.environment must be set (select a profile: local, dev, staging, prod)");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException("aegis.roles must name at least one role");
        }
        roles = Set.copyOf(EnumSet.copyOf(roles));
        applications = applications == null ? Map.of() : Map.copyOf(applications);
        applications.forEach((name, app) -> app.validate("aegis.applications." + name));
        Map<String, ApplicationProperties> known = applications;
        connections = connections == null
                ? Map.of()
                : connections.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                e -> e.getValue().validated("aegis.connections." + e.getKey(), known.keySet())));
    }

    public boolean runs(Role role) {
        return roles.contains(role);
    }

    /**
     * One provider account.
     *
     * @param secrets secret lists by name, newest first; empty entries (an unused "previous" slot) are
     *     dropped, but a name with no value left is an error
     */
    public record ConnectionProperties(
            String adapter,
            String application,
            URI baseUrl,
            Map<String, String> settings,
            Map<String, List<String>> secrets) {

        ConnectionProperties validated(String path, Set<String> applications) {
            require(adapter, path + ".adapter");
            require(application, path + ".application");
            if (!applications.contains(application)) {
                throw new IllegalStateException(path + ".application names an unknown application");
            }
            Map<String, List<String>> kept = secrets == null
                    ? Map.of()
                    : secrets.entrySet().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey, e -> secretList(path + ".secrets." + e.getKey(), e.getValue())));
            return new ConnectionProperties(
                    adapter, application, baseUrl, settings == null ? Map.of() : Map.copyOf(settings), kept);
        }

        private static List<String> secretList(String path, List<String> values) {
            List<String> kept = values == null
                    ? List.of()
                    : values.stream().filter(v -> v != null && !v.isBlank()).toList();
            if (kept.isEmpty()) {
                throw new IllegalStateException(path + " is empty");
            }
            kept.forEach(v -> requireLength(v, MIN_PROVIDER_SECRET, path));
            return kept;
        }

        @Override
        public String toString() {
            return "Connection[adapter=" + adapter + ", application=" + application + ", secrets="
                    + (secrets == null ? Set.of() : secrets.keySet()) + "]";
        }
    }

    /** One protected application. */
    public record ApplicationProperties(
            String apiKey,
            URI forwardUrl,
            URI returnUrl,
            String clientCertSubject,
            String signingSecret,
            String jwePublicKey) {

        void validate(String path) {
            requireLength(apiKey, MIN_APPLICATION_SECRET, path + ".api-key");
            requireLength(signingSecret, MIN_APPLICATION_SECRET, path + ".signing-secret");
            if (forwardUrl == null) {
                throw new IllegalStateException(path + ".forward-url must be set");
            }
        }

        @Override
        public String toString() {
            return "Application[forwardUrl=" + forwardUrl + ", returnUrl=" + returnUrl + "]";
        }
    }

    private static void require(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(path + " must be set");
        }
    }

    private static void requireLength(String value, int min, String path) {
        require(value, path);
        if (value.strip().length() < min) {
            throw new IllegalStateException(path + " is shorter than " + min + " characters");
        }
    }
}
