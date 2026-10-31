package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AegisPropertiesTest {

    @Test
    void reportsWhichRolesThisProcessRuns() {
        AegisProperties properties =
                new AegisProperties(DeploymentEnvironment.LOCAL, Set.of(Role.INGRESS, Role.CHECKER));

        assertThat(properties.runs(Role.INGRESS)).isTrue();
        assertThat(properties.runs(Role.ADMIN)).isFalse();
    }

    @Test
    void refusesToStartWithoutRoles() {
        assertThatThrownBy(() -> new AegisProperties(DeploymentEnvironment.LOCAL, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aegis.roles");
        assertThatThrownBy(() -> new AegisProperties(DeploymentEnvironment.LOCAL, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAnEnvironment() {
        assertThatThrownBy(() -> new AegisProperties(null, Set.of(Role.ADMIN)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void onlyProdIsProduction() {
        assertThat(DeploymentEnvironment.PROD.isProduction()).isTrue();
        assertThat(DeploymentEnvironment.STAGING.isProduction()).isFalse();
    }
}
