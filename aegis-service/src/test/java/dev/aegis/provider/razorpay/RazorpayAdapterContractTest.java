package dev.aegis.provider.razorpay;

import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderAdapterContract;
import dev.aegis.provider.spi.RawMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RazorpayAdapterContractTest extends ProviderAdapterContract {

    @Override
    protected ProviderAdapter adapter() {
        return new RazorpayAdapter();
    }

    /** Both secret lists hold the same current/previous pair, so one contract covers both channels. */
    @Override
    protected Connection connection() {
        Connection base = Razorpay.connection();
        return new Connection(
                base.name(),
                base.adapter(),
                base.application(),
                base.baseUrl(),
                base.settings(),
                new dev.aegis.provider.spi.SecretSet(Map.of(
                        "key-secret",
                        base.secrets().all("webhook"),
                        "webhook",
                        base.secrets().all("webhook"))));
    }

    @Override
    protected String currentSecret() {
        return Razorpay.WEBHOOK_SECRET;
    }

    @Override
    protected String previousSecret() {
        return Razorpay.WEBHOOK_SECRET_PREVIOUS;
    }

    @Override
    protected List<Channel> channels() {
        return List.of(Channel.SERVER, Channel.BROWSER);
    }

    @Override
    protected RawMessage signedWith(Channel channel, String secret) {
        return channel == Channel.SERVER
                ? Razorpay.webhook(Razorpay.event("payment.captured", "captured", 49_900), secret, "evt_1")
                : Razorpay.callback("order_X", "pay_Y", secret);
    }

    @Override
    protected RawMessage withoutSignature(Channel channel, RawMessage message) {
        if (channel == Channel.BROWSER) {
            return RawMessage.of(Map.of(), message.text().replaceAll("&razorpay_signature=[^&]*", ""));
        }
        Map<String, List<String>> headers = new LinkedHashMap<>(message.headers());
        headers.remove("X-Razorpay-Signature");
        return new RawMessage(headers, message.body());
    }

    @Override
    protected RawMessage signedGarbage(Channel channel) {
        return channel == Channel.SERVER
                ? Razorpay.webhook("<html>not json</html>", Razorpay.WEBHOOK_SECRET, "evt_2")
                : RawMessage.of(Map.of(), "razorpay_signature=abc");
    }
}
