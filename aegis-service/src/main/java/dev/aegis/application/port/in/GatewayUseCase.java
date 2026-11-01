package dev.aegis.application.port.in;

import dev.aegis.domain.message.Verdict;
import dev.aegis.provider.spi.CallKind;
import dev.aegis.provider.spi.RawMessage;

/**
 * The application's outbound calls (LLD §4): validations, payment requests and status checks. The
 * only way Aegis ever calls a provider (DECISIONS D2).
 */
public interface GatewayUseCase {

    /**
     * @param application the authenticated caller
     * @param request the provider-native request the application would have sent itself
     * @throws UnknownConnectionException no such connection
     * @throws ForbiddenException the connection belongs to another application
     * @throws BadRequestException the provider does not support this call, or the request is unreadable
     * @throws UnavailableException the provider could not be reached
     */
    Answer call(String application, String connection, CallKind kind, RawMessage request);

    /**
     * What the application gets back: the provider's reply as it came ({@code sent}), or Aegis's
     * refusal before anything was sent ({@code !sent}), and Aegis's verdict either way.
     */
    record Answer(boolean sent, int status, String contentType, byte[] body, Verdict verdict) {}
}
