package dev.aegis.application.port.in;

/** The caller is authenticated but may not use this connection (HTTP 403, LLD T16). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
