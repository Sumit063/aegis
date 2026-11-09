package dev.aegis.provider.npci;

import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderAdapterContract;
import dev.aegis.provider.spi.RawMessage;
import java.time.Instant;
import java.util.List;

class UpiAdapterContractTest extends ProviderAdapterContract {

    @Override
    protected ProviderAdapter adapter() {
        return new UpiAdapter();
    }

    @Override
    protected Connection connection() {
        return NpciMessages.connection("upi", "upi");
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
                NpciMessages.respPay("V1", "T1", "PAY", null, "SUCCESS", "499.00", "M1", Instant.now()), key);
    }

    @Override
    protected RawMessage withoutSignature(Channel channel, RawMessage message) {
        return NpciMessages.withoutSignature(message);
    }

    @Override
    protected RawMessage signedGarbage(Channel channel) {
        return NpciMessages.signed(
                "<upi:RespPay xmlns:upi=\"http://npci.org/upi/schema/\"><Head msgId=\"M\"/></upi:RespPay>",
                NpciMessages.CURRENT);
    }
}
