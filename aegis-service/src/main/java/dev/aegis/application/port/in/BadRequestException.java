package dev.aegis.application.port.in;

/** The request cannot be carried: unsupported by the provider, or missing what Aegis must check (HTTP 400). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
