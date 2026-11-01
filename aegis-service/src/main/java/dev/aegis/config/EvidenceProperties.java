package dev.aegis.config;

import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys that encrypt stored message bodies ({@code aegis.evidence}). Each is base64 of 32 random
 * bytes ({@code openssl rand -base64 32}); {@code current} seals new values, every key opens old ones.
 * Fails closed: no key, an unknown current key or a key of the wrong size stops startup.
 */
@ConfigurationProperties("aegis.evidence")
public record EvidenceProperties(String current, Map<String, String> keys) {

    public EvidenceProperties {
        if (keys == null || keys.isEmpty() || current == null || !keys.containsKey(current)) {
            throw new IllegalStateException(
                    "aegis.evidence.current must name one of aegis.evidence.keys (set AEGIS_EVIDENCE_KEY)");
        }
        keys = Map.copyOf(keys);
        keys.forEach((id, key) -> decode(id, key));
    }

    public Map<String, byte[]> decoded() {
        return keys.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> decode(e.getKey(), e.getValue())));
    }

    private static byte[] decode(String id, String key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(key == null ? "" : key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("aegis.evidence.keys." + id + " is not base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("aegis.evidence.keys." + id + " must be 32 bytes");
        }
        return raw;
    }

    @Override
    public String toString() {
        return "EvidenceProperties[current=" + current + ", keys=" + keys.keySet() + "]";
    }
}
