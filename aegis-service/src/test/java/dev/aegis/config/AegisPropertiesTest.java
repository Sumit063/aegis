package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AegisPropertiesTest {

    @Test
    void reportsWhichRolesThisProcessRuns() {
        AegisProperties properties = new AegisProperties(Set.of(Role.INGRESS, Role.PROCESSOR));

        assertThat(properties.runs(Role.INGRESS)).isTrue();
        assertThat(properties.runs(Role.ADMIN)).isFalse();
    }

    @Test
    void refusesToStartWithoutRoles() {
        assertThatThrownBy(() -> new AegisProperties(Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aegis.roles");
        assertThatThrownBy(() -> new AegisProperties(null)).isInstanceOf(IllegalStateException.class);
    }
}
