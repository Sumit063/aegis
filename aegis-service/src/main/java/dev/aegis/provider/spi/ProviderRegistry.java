package dev.aegis.provider.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The installed provider adapters, by ID (Plugin + Registry). Adding a provider adds an adapter;
 * nothing in the core changes.
 */
public final class ProviderRegistry {

    private final Map<String, ProviderAdapter> adapters;

    public ProviderRegistry(Collection<ProviderAdapter> adapters) {
        this.adapters =
                adapters.stream().collect(Collectors.toUnmodifiableMap(ProviderAdapter::id, Function.identity()));
    }

    /** Every adapter on the class path. */
    public static ProviderRegistry installed() {
        return new ProviderRegistry(ServiceLoader.load(ProviderAdapter.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    public Optional<ProviderAdapter> adapter(String id) {
        return Optional.ofNullable(adapters.get(id));
    }

    public Collection<String> ids() {
        return adapters.keySet();
    }

    /**
     * Fails startup unless every connection names an installed adapter and has the secrets and
     * settings that adapter needs (fail closed, DECISIONS D17).
     */
    public void validate(Collection<Connection> connections, boolean production) {
        for (Connection connection : connections) {
            ProviderAdapter adapter = adapter(connection.adapter())
                    .orElseThrow(() -> new IllegalStateException("aegis.connections." + connection.name()
                            + ".adapter: no adapter named " + connection.adapter() + " (installed: " + ids() + ")"));
            List<String> missing = adapter.requiredSecrets().stream()
                    .filter(name -> !connection.secrets().has(name))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "aegis.connections." + connection.name() + ".secrets is missing " + missing);
            }
            List<String> unset = adapter.requiredSettings().stream()
                    .filter(name -> !connection.settings().containsKey(name))
                    .sorted()
                    .toList();
            if (!unset.isEmpty()) {
                throw new IllegalStateException(
                        "aegis.connections." + connection.name() + ".settings is missing " + unset);
            }
            adapter.validate(connection, production);
        }
    }
}
