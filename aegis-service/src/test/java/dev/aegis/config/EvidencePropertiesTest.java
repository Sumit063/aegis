package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidencePropertiesTest {

    private static final String KEY = "bG9jYWwtb25seS1ldmlkZW5jZS1rZXktMDAwMDAwMDA=";

    @Test
    void acceptsA32ByteKeyAndNeverPrintsIt() {
        EvidenceProperties properties = new EvidenceProperties("k1", Map.of("k1", KEY));

        assertThat(properties.decoded().get("k1")).hasSize(32);
        assertThat(properties.toString()).doesNotContain(KEY);
    }

    @Test
    void failsClosed() {
        assertThatThrownBy(() -> new EvidenceProperties("k1", Map.of())).hasMessageContaining("AEGIS_EVIDENCE_KEY");
        assertThatThrownBy(() -> new EvidenceProperties("k2", Map.of("k1", KEY)))
                .hasMessageContaining("current");
        assertThatThrownBy(() -> new EvidenceProperties("k1", Map.of("k1", "c2hvcnQ=")))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new EvidenceProperties("k1", Map.of("k1", "not base64!")))
                .hasMessageContaining("not base64");
    }
}
