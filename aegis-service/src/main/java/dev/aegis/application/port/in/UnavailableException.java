package dev.aegis.application.port.in;

/** A dependency Aegis needs before it may answer is down (HTTP 503); the caller should retry. */
public class UnavailableException extends RuntimeException {

    public UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
