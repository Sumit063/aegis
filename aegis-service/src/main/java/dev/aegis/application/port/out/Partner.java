package dev.aegis.application.port.out;

import dev.aegis.application.port.out.RateLimiterPort.Rate;
import dev.aegis.provider.spi.Secret;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A partner allowed to call an application's partner API through Aegis (LLD §11, §14).
 *
 * @param application the application whose {@code partner-url} it reaches
 * @param secrets request-signing secrets, newest first (rotation); any of them verifies
 * @param clientCertSubject required client-certificate subject, or null
 * @param rate the partner's rate over all its operations
 * @param operations what it may call: first path segment → methods and an optional own rate
 */
public record Partner(
        String name,
        String application,
        List<Secret> secrets,
        String clientCertSubject,
        Rate rate,
        Map<String, Grant> operations) {

    /** @param rate the operation's own rate, on top of the partner's, or null */
    public record Grant(Set<String> methods, Rate rate) {

        public Grant {
            methods = Set.copyOf(methods);
        }
    }

    public Partner {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(rate, "rate");
        secrets = List.copyOf(secrets);
        operations = Map.copyOf(operations);
        if (secrets.isEmpty()) {
            throw new IllegalArgumentException("a partner needs a signing secret");
        }
    }

    /** The methods granted for {@code operation}; empty if it was not granted. */
    public Set<String> granted(String operation) {
        Grant grant = operation == null ? null : operations.get(operation);
        return grant == null ? Set.of() : grant.methods();
    }
}
