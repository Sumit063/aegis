package dev.aegis.provider.spi;

/** Application-initiated calls Aegis can carry to a provider (LLD §4). */
public enum CallKind {
    /** Fixes the amount: create order, bill fetch, validate address. */
    VALIDATION,
    /** Server-to-server payment request (NPCI style). */
    PAY_REQUEST,
    /** Asks the provider for a payment's current state. */
    STATUS_CHECK
}
