package dev.aegis.provider.spi;

/**
 * A provider's reply to an application's call failed verification (its signature). The call's
 * result is {@code BLOCKED BAD_SIGNATURE}; nothing in the reply is believed (DECISIONS D33).
 */
public class UntrustedReplyException extends RuntimeException {

    public UntrustedReplyException(String message) {
        super(message);
    }
}
