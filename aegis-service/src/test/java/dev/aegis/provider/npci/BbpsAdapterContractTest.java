package dev.aegis.provider.npci;

import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderAdapterContract;
import dev.aegis.provider.spi.RawMessage;
import java.time.Instant;
import java.util.List;

class BbpsAdapterContractTest extends ProviderAdapterContract {

    @Override
    protected ProviderAdapter adapter() {
        return new BbpsAdapter();
    }

    @Override
    protected Connection connection() {
        return NpciMessages.connection("bbps", "bbps");
    }

    @Override
    protected String currentSecret() {
        return NpciMessages.CURRENT;
    }

    @Override
    protected String previousSecret() {
        return NpciMessages.PREVIOUS;
    }

    /** Server to server only: there is no browser leg. */
    @Override
    protected List<Channel> channels() {
        return List.of(Channel.SERVER);
    }

    @Override
    protected RawMessage signedWith(Channel channel, String key) {
        return NpciMessages.signed(
                NpciMessages.billPaymentResponse("R1", "T1", "Successful", "000", 84_000, "M1", Instant.now()), key);
    }

    @Override
    protected RawMessage withoutSignature(Channel channel, RawMessage message) {
        return NpciMessages.withoutSignature(message);
    }

    @Override
    protected RawMessage signedGarbage(Channel channel) {
        return NpciMessages.signed(
                "<bbps:BillPaymentResponse xmlns:bbps=\"http://bbps.org/schema\"><Head msgId=\"M\"/></bbps:BillPaymentResponse>",
                NpciMessages.CURRENT);
    }
}
