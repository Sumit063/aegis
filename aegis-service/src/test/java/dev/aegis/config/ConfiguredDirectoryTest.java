package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfiguredDirectoryTest {

    private final ConfiguredDirectory directory = new ConfiguredDirectory(
            AegisPropertiesTest.properties(AegisPropertiesTest.razorpay(AegisPropertiesTest.WEBHOOK_SECRET)));

    @Test
    void exposesConnectionsWithTheirSecrets() {
        var connection = directory.connection("razorpay").orElseThrow();

        assertThat(connection.adapter()).isEqualTo("razorpay");
        assertThat(connection.setting("key-id")).isEqualTo("rzp_test_x");
        assertThat(connection.secrets().current("webhook").value()).isEqualTo(AegisPropertiesTest.WEBHOOK_SECRET);
        assertThat(connection.toString()).doesNotContain(AegisPropertiesTest.WEBHOOK_SECRET);
        assertThat(directory.connection("stripe")).isEmpty();
        assertThat(directory.connections()).hasSize(1);
    }

    @Test
    void findsAnApplicationByItsKey() {
        assertThat(directory.applicationByKey(AegisPropertiesTest.APP_SECRET))
                .hasValueSatisfying(app -> assertThat(app.name()).isEqualTo("shop"));
        assertThat(directory.applicationByKey("wrong")).isEmpty();
        assertThat(directory.applicationByKey(null)).isEmpty();
        assertThat(directory.application("shop").orElseThrow().apiKey().toString())
                .isEqualTo("****");
    }
}
