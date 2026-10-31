package dev.aegis.config;

/**
 * Where this process runs. Selected by the Spring profile of the same name.
 *
 * <p>Drives environment-specific safety rules, e.g. production must never run with gateway test
 * keys and non-production must never hold live keys (enforced by configuration validation).
 */
public enum DeploymentEnvironment {
    LOCAL,
    DEV,
    STAGING,
    PROD;

    public boolean isProduction() {
        return this == PROD;
    }
}
