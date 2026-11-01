package dev.aegis.domain.message;

/** Where a payment message came from. */
public enum MessageType {
    /** Payment request the application sent through Aegis; holds the provider's synchronous reply. */
    PAY_REQUEST,
    /** Status check the application asked for; holds the provider's reply. */
    STATUS_CHECK,
    /** Result carried back by the customer's browser. */
    CALLBACK,
    /** Server-to-server notification from the provider. */
    WEBHOOK,
    /** Asynchronous response to an earlier request (NPCI style). */
    ASYNC_RESPONSE;

    /** Messages the provider sends on its own; when they pass, the application receives them. */
    public boolean forwarded() {
        return this == CALLBACK || this == WEBHOOK || this == ASYNC_RESPONSE;
    }
}
