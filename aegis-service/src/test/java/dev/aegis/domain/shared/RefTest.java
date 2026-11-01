package dev.aegis.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefTest {

    @Test
    void keepsAValidReference() {
        assertThat(Ref.of("pay_Q81xTEST0001").value()).isEqualTo("pay_Q81xTEST0001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "pay\nX", "evt\u0000"})
    void rejectsBlankOrControlCharacters(String value) {
        assertThatThrownBy(() -> Ref.of(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongReferences() {
        assertThatThrownBy(() -> Ref.of("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Ref.of("x".repeat(128)).value()).hasSize(128);
    }

    @Test
    void unverifiedIdsCannotCollideWithProviderIds() {
        Ref id = Ref.unverified(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertThat(id.value()).isEqualTo("unverified:00000000-0000-0000-0000-000000000001");
        assertThat(id.isUnverified()).isTrue();
        assertThat(Ref.of("evt_123").isUnverified()).isFalse();
    }
}
