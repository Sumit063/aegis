package dev.aegis.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.application.port.in.ReceiveProviderMessageUseCase.Receipt;
import dev.aegis.application.port.in.UnavailableException;
import dev.aegis.application.port.in.UnknownConnectionException;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.provider.example.Example;
import dev.aegis.provider.example.ExampleAdapter;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderRegistry;
import dev.aegis.provider.spi.RawMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngressServiceTest {

    private static final String BODY =
            "{\"id\":\"evt_1\",\"order\":\"ord_1\",\"payment\":\"pay_1\",\"status\":\"success\"}";
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final Fakes.IngressLog log = new Fakes.IngressLog();

    private IngressService service(ProviderAdapter adapter) {
        return new IngressService(
                Fakes.directory(List.of(Example.connection()), List.of()),
                new ProviderRegistry(List.of(adapter)),
                log,
                Fakes.CIPHER,
                Fakes.METRICS,
                new IngressLimits(6_000, 500, 64, 3),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesAGenuineMessageSealedThenAcknowledges() {
        Receipt receipt = service(new ExampleAdapter()).receive("example", Channel.SERVER, Example.signed(BODY));

        assertThat(receipt.verified()).isTrue();
        assertThat(receipt.ack().status()).isEqualTo(200);
        IngressRecord record = log.records.getFirst();
        assertThat(record.verified()).isTrue();
        assertThat(record.key()).isEqualTo("example:pay_1");
        assertThat(record.sealedBody()).isEqualTo("sealed:" + BODY);
        assertThat(record.receivedAt()).isEqualTo(NOW);
    }

    /** T2: a forged message is logged, flagged, and never acknowledged. */
    @Test
    void logsAForgeryFlaggedAndRefusesIt() {
        RawMessage forged = RawMessage.of(Map.of(ExampleAdapter.SIGNATURE, "00"), BODY);

        Receipt receipt = service(new ExampleAdapter()).receive("example", Channel.SERVER, forged);

        assertThat(receipt.verified()).isFalse();
        assertThat(receipt.ack()).isNull();
        assertThat(log.records.getFirst().verified()).isFalse();
        assertThat(log.records.getFirst().key()).isEqualTo("example");
    }

    /** T13: nothing is acknowledged that is not stored; the provider retries. */
    @Test
    void refusesToAcknowledgeWhenTheLogIsDown() {
        log.down = true;

        assertThatThrownBy(() -> service(new ExampleAdapter()).receive("example", Channel.SERVER, Example.signed(BODY)))
                .isInstanceOf(UnavailableException.class);
        assertThat(service(new ExampleAdapter())
                        .receive("example", Channel.SERVER, RawMessage.of(Map.of(), BODY))
                        .verified())
                .isFalse();
    }

    /** A flood of forgeries is refused; only a bounded sample is kept as evidence (review item 22). */
    @Test
    void keepsOnlyABoundedSampleOfForgeries() {
        IngressService service = service(new ExampleAdapter());
        RawMessage forged = RawMessage.of(Map.of(ExampleAdapter.SIGNATURE, "00"), BODY);

        for (int i = 0; i < 100; i++) {
            assertThat(service.receive("example", Channel.SERVER, forged).verified())
                    .isFalse();
        }

        assertThat(log.records).hasSize(3);
        assertThat(service.receive("example", Channel.SERVER, Example.signed(BODY))
                        .verified())
                .as("genuine messages are never limited by the forgery quota")
                .isTrue();
    }

    @Test
    void unknownConnection() {
        assertThatThrownBy(() -> service(new ExampleAdapter()).receive("nope", Channel.SERVER, Example.signed(BODY)))
                .isInstanceOf(UnknownConnectionException.class);
    }

    /** Fail closed: an adapter that crashes while verifying has not verified anything. */
    @Test
    void anAdapterErrorMeansNotVerified() {
        ExampleAdapter crashing = new ExampleAdapter() {
            @Override
            public boolean verify(Channel channel, RawMessage message, Connection connection) {
                throw new IllegalStateException("boom");
            }
        };

        assertThat(service(crashing)
                        .receive("example", Channel.SERVER, Example.signed(BODY))
                        .verified())
                .isFalse();
    }
}
