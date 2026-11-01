package dev.aegis.provider.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.PaymentMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shared contract every {@link ProviderAdapter} passes (Liskov substitution: any adapter can stand
 * in for any other). A provider's test extends this and supplies genuine messages.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ProviderAdapterContract {

    /** A secret the connection does not hold. */
    protected static final String UNKNOWN_SECRET = "a-secret-this-connection-never-had-0001";

    protected abstract ProviderAdapter adapter();

    /** A connection whose verification secrets are {@link #currentSecret()} then {@link #previousSecret()}. */
    protected abstract Connection connection();

    protected abstract String currentSecret();

    protected abstract String previousSecret();

    /** The channels this provider uses. */
    protected abstract List<Channel> channels();

    /** A genuine message on {@code channel}, signed with {@code secret} as the provider would. */
    protected abstract RawMessage signedWith(Channel channel, String secret);

    /** {@code message} with its signature removed. */
    protected abstract RawMessage withoutSignature(Channel channel, RawMessage message);

    /** A correctly signed message whose content cannot be read. */
    protected abstract RawMessage signedGarbage(Channel channel);

    @ParameterizedTest
    @MethodSource("channelsOf")
    void genuineMessagesVerify(Channel channel) {
        assertThat(adapter().verify(channel, signedWith(channel, currentSecret()), connection()))
                .isTrue();
    }

    /** T17: the previous secret keeps verifying until it is removed from configuration. */
    @ParameterizedTest
    @MethodSource("channelsOf")
    void previousSecretStillVerifies(Channel channel) {
        assertThat(adapter().verify(channel, signedWith(channel, previousSecret()), connection()))
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("channelsOf")
    void unknownSecretFails(Channel channel) {
        assertThat(adapter().verify(channel, signedWith(channel, UNKNOWN_SECRET), connection()))
                .isFalse();
    }

    /** T2 and CVE-2026-41432: a signature made with an empty key never verifies. */
    @ParameterizedTest
    @MethodSource("channelsOf")
    void emptyKeyFails(Channel channel) {
        assertThat(adapter().verify(channel, signedWith(channel, ""), connection()))
                .isFalse();
    }

    @ParameterizedTest
    @MethodSource("channelsOf")
    void missingSignatureFails(Channel channel) {
        RawMessage unsigned = withoutSignature(channel, signedWith(channel, currentSecret()));
        assertThat(adapter().verify(channel, unsigned, connection())).isFalse();
    }

    @ParameterizedTest
    @MethodSource("channelsOf")
    void tamperedBodyFails(Channel channel) {
        RawMessage genuine = signedWith(channel, currentSecret());
        byte[] body = genuine.body().clone();
        body[body.length / 2] ^= 1;
        assertThat(adapter().verify(channel, new RawMessage(genuine.headers(), body), connection()))
                .isFalse();
    }

    /** T3: no header (Host, forwarding headers) can make a forged message pass. */
    @ParameterizedTest
    @MethodSource("channelsOf")
    void trustedLookingHeadersDoNotHelp(Channel channel) {
        RawMessage unsigned = withoutSignature(channel, signedWith(channel, currentSecret()));
        Map<String, List<String>> headers = new LinkedHashMap<>(unsigned.headers());
        headers.put("Host", List.of("localhost"));
        headers.put("X-Forwarded-For", List.of("127.0.0.1"));
        assertThat(adapter().verify(channel, new RawMessage(headers, unsigned.body()), connection()))
                .isFalse();
    }

    @ParameterizedTest
    @MethodSource("channelsOf")
    void parsesIntoProviderNeutralTerms(Channel channel) {
        Instant now = Instant.now();
        PaymentMessage message = adapter().parse(channel, signedWith(channel, currentSecret()), connection(), now);

        assertThat(message.provider().value()).isEqualTo(connection().name());
        assertThat(message.receivedAt()).isEqualTo(now);
        assertThat(message.type())
                .isIn(
                        channel == Channel.BROWSER
                                ? List.of(MessageType.CALLBACK)
                                : List.of(MessageType.WEBHOOK, MessageType.ASYNC_RESPONSE));
    }

    @ParameterizedTest
    @MethodSource("channelsOf")
    void unreadableMessagesAreReportedAsUnparseable(Channel channel) {
        assertThatThrownBy(() -> adapter().parse(channel, signedGarbage(channel), connection(), Instant.now()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @ParameterizedTest
    @MethodSource("channelsOf")
    void acknowledgesWithSuccess(Channel channel) {
        assertThat(adapter().ack(channel, signedWith(channel, currentSecret())).status())
                .isBetween(200, 299);
    }

    @Test
    void declaresItsNeedsAndLimits() {
        assertThat(adapter().id()).isNotBlank();
        assertThat(adapter().requiredSecrets()).isNotEmpty();
        assertThat(adapter().freshness()).isPositive();
        assertThat(adapter().enquiryLimits()).isNotNull();
    }

    @Test
    void registryAcceptsTheConnectionAndRefusesOneMissingASecret() {
        ProviderRegistry registry = new ProviderRegistry(List.of(adapter()));
        registry.validate(List.of(connection()), false);

        String required = adapter().requiredSecrets().iterator().next();
        Map<String, List<Secret>> fewer = connection().secrets().values().entrySet().stream()
                .filter(e -> !e.getKey().equals(required))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Connection incomplete = new Connection(
                connection().name(),
                connection().adapter(),
                connection().application(),
                connection().baseUrl(),
                connection().settings(),
                new SecretSet(fewer));
        assertThatThrownBy(() -> registry.validate(List.of(incomplete), false))
                .hasMessageContaining("is missing [" + required + "]");
    }

    protected List<Channel> channelsOf() {
        return channels();
    }

    protected static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
