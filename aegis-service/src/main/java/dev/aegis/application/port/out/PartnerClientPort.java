package dev.aegis.application.port.out;

/**
 * Calls an application's partner API (LLD §14): the partner's request, signed by Aegis with the
 * application's secret and naming the partner and the request ID. Never called inside a transaction.
 */
public interface PartnerClientPort {

    /**
     * @param path below the application's {@code partner-url}, with the query (e.g. {@code /recharges?x=1})
     * @param contentType the partner's, or null
     */
    record Request(String partner, String requestId, String method, String path, String contentType, byte[] body) {}

    record Response(int status, String contentType, byte[] body) {}

    /**
     * @throws NotSentException the application was never reached: nothing can have been executed
     * @throws NoAnswerException the request may have reached it, but no answer came back in time
     */
    Response send(Application application, Request request);

    final class NotSentException extends RuntimeException {
        public NotSentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class NoAnswerException extends RuntimeException {
        public NoAnswerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
