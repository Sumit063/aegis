package dev.aegis.application.port.out;

import java.util.Set;

/**
 * Operational settings that may change at runtime. Secrets, addresses and certificates never do
 * (they change only with a deployment).
 *
 * @param pausedApplications applications whose deliveries are held (they queue, nothing is lost)
 */
public record Settings(Set<String> pausedApplications) {

    public static final Settings DEFAULTS = new Settings(Set.of());

    public Settings {
        pausedApplications = pausedApplications == null ? Set.of() : Set.copyOf(pausedApplications);
    }
}
