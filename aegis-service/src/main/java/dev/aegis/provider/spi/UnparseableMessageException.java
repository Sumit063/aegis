package dev.aegis.provider.spi;

/** A message was authentic but could not be read; it is stored as {@code BLOCKED UNPARSEABLE}. */
public class UnparseableMessageException extends RuntimeException {

    public UnparseableMessageException(String message) {
        super(message);
    }

    public UnparseableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
