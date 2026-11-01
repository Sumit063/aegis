package dev.aegis.domain.message;

/**
 * What a message says happened to a payment, in provider-neutral terms. Which outcome may follow
 * which is decided by {@link dev.aegis.domain.check.Transitions}, not by an ordering of this enum.
 */
public enum Outcome {
    /** Authorized or in progress; money not yet final (e.g. Razorpay {@code authorized}, NPCI deemed). */
    PENDING,
    /** Captured: the money is the merchant's. */
    SUCCESS,
    /** The attempt failed. */
    FAILED,
    /** Money returned to the customer, fully or partly; each refund is its own fact. */
    REFUNDED,
    /** The payment was reversed by the network or bank (e.g. NPCI reversal, chargeback). */
    REVERSED,
    /** An authenticated event Aegis does not interpret; never forwarded as payment news. */
    OTHER
}
