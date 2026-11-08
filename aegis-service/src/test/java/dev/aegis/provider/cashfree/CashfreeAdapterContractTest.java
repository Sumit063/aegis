package dev.aegis.provider.cashfree;

import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderAdapterContract;
import dev.aegis.provider.spi.RawMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class CashfreeAdapterContractTest extends ProviderAdapterContract {

    @Override
    protected ProviderAdapter adapter() {
        return new CashfreeAdapter();
    }

    @Override
    protected Connection connection() {
        return Cashfree.connection();
    }

    @Override
    protected String currentSecret() {
        return Cashfree.CLIENT_SECRET;
    }

    @Override
    protected String previousSecret() {
        return Cashfree.CLIENT_SECRET_PREVIOUS;
    }

    /** Cashfree has no signed browser callback. */
    @Override
    protected List<Channel> channels() {
        return List.of(Channel.SERVER);
    }

    @Override
    protected RawMessage signedWith(Channel channel, String secret) {
        return Cashfree.webhook(Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", "2.00"), secret, "idem-1");
    }

    @Override
    protected RawMessage withoutSignature(Channel channel, RawMessage message) {
        Map<String, List<String>> headers = new LinkedHashMap<>(message.headers());
        headers.remove("x-webhook-signature");
        return new RawMessage(headers, message.body());
    }

    @Override
    protected RawMessage signedGarbage(Channel channel) {
        return Cashfree.webhook("<html>not json</html>");
    }
}
