package dev.aegis.config;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root of the {@code aegis.*} configuration.
 *
 * <p>Fails closed: a process with no roles is a misconfiguration, not an idle service.
 */
@ConfigurationProperties(prefix = "aegis")
public record AegisProperties(Set<Role> roles) {

    public AegisProperties {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException("aegis.roles must name at least one role");
        }
        roles = Set.copyOf(EnumSet.copyOf(roles));
    }

    public boolean runs(Role role) {
        return roles.contains(role);
    }
}
