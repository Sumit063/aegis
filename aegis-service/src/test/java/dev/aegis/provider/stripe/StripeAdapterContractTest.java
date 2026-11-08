package dev.aegis.provider.stripe;

import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderAdapterContract;
import dev.aegis.provider.spi.RawMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class StripeAdapterContractTest extends ProviderAdapterContract {

    @Override
    protected ProviderAdapter adapter() {
        return new StripeAdapter();
    }

    @Override
    protected Connection connection() {
        return Stripe.connection();
    }

    @Override
    protected String currentSecret() {
        return Stripe.WEBHOOK_SECRET;
    }

    @Override
    protected String previousSecret() {
        return Stripe.WEBHOOK_SECRET_PREVIOUS;
    }

    /** Stripe has no signed browser callback. */
    @Override
    protected List<Channel> channels() {
        return List.of(Channel.SERVER);
    }

    @Override
    protected RawMessage signedWith(Channel channel, String secret) {
        return Stripe.webhook(
                Stripe.event("payment_intent.succeeded", Stripe.intent("pi_1", "succeeded", 2_000, 2_000)), secret);
    }

    @Override
    protected RawMessage withoutSignature(Channel channel, RawMessage message) {
        Map<String, List<String>> headers = new LinkedHashMap<>(message.headers());
        headers.remove("Stripe-Signature");
        return new RawMessage(headers, message.body());
    }

    @Override
    protected RawMessage signedGarbage(Channel channel) {
        return Stripe.webhook("<html>not json</html>", Stripe.WEBHOOK_SECRET);
    }
}
