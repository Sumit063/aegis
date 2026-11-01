package dev.aegis.application.port.in;

import dev.aegis.provider.spi.Ack;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.RawMessage;

/**
 * Ingress (LLD §3 steps 1–5): verify a provider message, write it to the ingress log, and say what to
 * answer. Nothing is acknowledged before it is durably stored.
 */
public interface ReceiveProviderMessageUseCase {

    /**
     * @throws UnknownConnectionException when no connection has this name
     * @throws UnavailableException when the message could not be stored (the provider will retry)
     */
    Receipt receive(String connection, Channel channel, RawMessage message);

    /** {@code ack} is what the provider expects when {@code verified}; a forged message gets nothing. */
    record Receipt(boolean verified, Ack ack) {}
}
