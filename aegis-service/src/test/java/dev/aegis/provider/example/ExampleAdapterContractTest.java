package dev.aegis.provider.example;

import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderAdapterContract;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Proves the contract suite itself: a correct adapter passes it. */
class ExampleAdapterContractTest extends ProviderAdapterContract {

    static final String CURRENT = "example-webhook-secret-current-001";
    static final String PREVIOUS = "example-webhook-secret-previous-01";

    static Connection exampleConnection() {
        return new Connection(
                "example",
                "example",
                "shop",
                URI.create("http://provider.test"),
                Map.of(),
                new SecretSet(Map.of(
                        "webhook", List.of(new Secret(CURRENT), new Secret(PREVIOUS)),
                        "api-key", List.of(new Secret("example-api-key-for-tests-0000001")))));
    }

    @Override
    protected ProviderAdapter adapter() {
        return new ExampleAdapter();
    }

    @Override
    protected Connection connection() {
        return exampleConnection();
    }

    @Override
    protected String currentSecret() {
        return CURRENT;
    }

    @Override
    protected String previousSecret() {
        return PREVIOUS;
    }

    @Override
    protected List<Channel> channels() {
        return List.of(Channel.SERVER, Channel.BROWSER);
    }

    @Override
    protected RawMessage signedWith(Channel channel, String secret) {
        String body = """
                {"id":"evt_1","order":"ord_1","payment":"pay_1","status":"success","amount":49900,"currency":"INR","sent_at":%d}""".formatted(Instant.now().getEpochSecond());
        return RawMessage.of(Map.of(ExampleAdapter.SIGNATURE, ExampleAdapter.sign(secret, utf8(body))), body);
    }

    @Override
    protected RawMessage withoutSignature(Channel channel, RawMessage message) {
        Map<String, List<String>> headers = new LinkedHashMap<>(message.headers());
        headers.remove(ExampleAdapter.SIGNATURE);
        return new RawMessage(headers, message.body());
    }

    @Override
    protected RawMessage signedGarbage(Channel channel) {
        return RawMessage.of(
                Map.of(ExampleAdapter.SIGNATURE, ExampleAdapter.sign(CURRENT, utf8("not json"))), "not json");
    }
}
