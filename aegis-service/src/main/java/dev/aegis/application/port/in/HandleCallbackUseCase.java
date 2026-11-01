package dev.aegis.application.port.in;

import dev.aegis.provider.spi.RawMessage;
import java.net.URI;

/**
 * A Checkout result carried by the customer's browser (LLD §3, browser callback): checked inline,
 * stored, and the browser sent on to the application's return page with a signed result.
 */
public interface HandleCallbackUseCase {

    /**
     * @return where to send the browser
     * @throws UnknownConnectionException when no connection has this name
     */
    URI handle(String connection, RawMessage message);
}
