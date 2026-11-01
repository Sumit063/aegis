package dev.aegis.provider.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

    @Test
    void findsAdaptersOnTheClassPath() {
        assertThat(ProviderRegistry.installed().ids()).contains("example");
    }

    @Test
    void refusesAConnectionForAnAdapterThatIsNotInstalled() {
        Connection stripe =
                new Connection("stripe", "stripe", "shop", URI.create("http://x"), Map.of(), new SecretSet(Map.of()));

        assertThatThrownBy(() -> ProviderRegistry.installed().validate(List.of(stripe), false))
                .hasMessageContaining("no adapter named stripe");
    }

    @Test
    void rawMessageHeadersIgnoreCaseAndNeverPrintTheBody() {
        RawMessage message = RawMessage.of(Map.of("X-Razorpay-Signature", "abc"), "secret body");

        assertThat(message.header("x-razorpay-signature")).contains("abc");
        assertThat(message.header("missing")).isEmpty();
        assertThat(message.toString()).doesNotContain("secret body");
        assertThat(message).isEqualTo(RawMessage.of(Map.of("X-Razorpay-Signature", "abc"), "secret body"));
    }

    @Test
    void secretsNeverPrint() {
        Secret secret = new Secret("do-not-print-me-0000000001");
        SecretSet set = new SecretSet(Map.of("webhook", List.of(secret)));

        assertThat(secret.toString()).isEqualTo("****");
        assertThat(set.toString()).isEqualTo("SecretSet[webhook]");
        assertThat(secret.matches("do-not-print-me-0000000001")).isTrue();
        assertThat(secret.matches("wrong")).isFalse();
        assertThat(set.current("webhook")).isEqualTo(secret);
        assertThatThrownBy(() -> set.all("api-key")).hasMessageContaining("api-key");
    }
}
