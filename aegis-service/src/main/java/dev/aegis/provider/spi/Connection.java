package dev.aegis.provider.spi;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * One configured provider account (LLD §1): which adapter speaks to it, which application it belongs
 * to, where its API is, its non-secret settings (such as a public key ID) and its secrets.
 */
public record Connection(
        String name, String adapter, String application, URI baseUrl, Map<String, String> settings, SecretSet secrets) {

    public Connection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(secrets, "secrets");
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public String setting(String key) {
        String value = settings.get(key);
        if (value == null) {
            throw new IllegalStateException("connection " + name + " has no setting " + key);
        }
        return value;
    }
}
