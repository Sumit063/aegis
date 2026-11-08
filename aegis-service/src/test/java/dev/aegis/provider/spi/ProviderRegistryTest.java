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
        assertThat(ProviderRegistry.installed().ids()).contains("example", "razorpay", "stripe");
    }

    @Test
    void refusesAConnectionForAnAdapterThatIsNotInstalled() {
        Connection unknown =
                new Connection("nope", "nope", "shop", URI.create("http://x"), Map.of(), new SecretSet(Map.of()));

        assertThatThrownBy(() -> ProviderRegistry.installed().validate(List.of(unknown), false))
                .hasMessageContaining("no adapter named nope");
    }

    @Test
    void rawMessageHeadersIgnoreCaseAndNeverPrintTheBody() {
        RawMessage message = RawMessage.of(Map.of("X-Razorpay-Signature", "abc"), "secret body");

        assertThat(message.header("x-razorpay-signature")).contains("abc");
        assertThat(message.header("missing")).isEmpty();
        assertThat(message.toString()).doesNotContain("secret body");
        assertThat(message).isEqualTo(RawMessage.of(Map.of("X-Razorpay-Signature", "abc"), "secret body"));
    }

    /** RFC 4231 test case 2: the shared HMAC every adapter signs and verifies with. */
    @Test
    void secretsSignAndVerifyWithHmacSha256() {
        Secret key = new Secret("Jefe");
        byte[] data = "what do ya want for nothing?".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

        assertThat(key.hmacSha256Hex(data)).isEqualTo(expected);
        assertThat(key.signed(data, expected)).isTrue();
        assertThat(key.signed(data, " " + expected + " ")).isTrue();
        assertThat(key.signed(data, expected.replace('5', '6'))).isFalse();
        assertThat(key.signed(data, null)).isFalse();
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
