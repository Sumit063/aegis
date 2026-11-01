package dev.aegis.application.port.in;

/** No connection of that name is configured (HTTP 404). */
public class UnknownConnectionException extends RuntimeException {

    public UnknownConnectionException(String connection) {
        super("no connection named " + connection);
    }
}
