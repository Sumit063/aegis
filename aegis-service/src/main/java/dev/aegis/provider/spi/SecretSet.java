package dev.aegis.provider.spi;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * A connection's secrets by name. Each name holds a list, newest first, so a secret can be rotated
 * without downtime: verification accepts any entry, signing uses the first.
 */
public record SecretSet(Map<String, List<Secret>> values) {

    public SecretSet {
        values = values.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /** The current secret, used to sign. */
    public Secret current(String name) {
        return all(name).getFirst();
    }

    /** Every accepted secret, used to verify. */
    public List<Secret> all(String name) {
        List<Secret> secrets = values.get(name);
        if (secrets == null || secrets.isEmpty()) {
            throw new NoSuchElementException("no secret named " + name);
        }
        return secrets;
    }

    public boolean has(String name) {
        return values.containsKey(name) && !values.get(name).isEmpty();
    }

    @Override
    public String toString() {
        return "SecretSet" + values.keySet();
    }
}
