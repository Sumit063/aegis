package dev.aegis.lab.shop;

/**
 * Integration flaws found in real payment plugins (EXPLOIT-LAB §3). Each switch removes exactly one
 * protection; with no switch on, the shop is a careful integration.
 */
public enum Flaw {
    /** No webhook secret configured, and an HMAC with an empty key is accepted. */
    F1,
    /** {@code order.paid} webhooks skip signature verification. */
    F2,
    /** The webhook's order is taken from {@code notes.shop_order}, and the amount is not compared. */
    F3,
    /** Requests that claim {@code Host: localhost} skip signature verification. */
    F4,
    /** Webhook event IDs are not remembered, so a repeated event fulfils again. */
    F5,
    /** {@code payment.failed} cancels the order even after it was paid. */
    F6,
    /** The Checkout callback trusts a {@code status=success} field instead of the signature. */
    F7,
    /** The Checkout callback fulfils the order named in the URL, not the order that was signed. */
    F8,
    /** The callback and the webhook each fulfil, without knowing about each other. */
    F9,
    /** Old webhooks are accepted: no time check once the event-ID memory has expired. */
    F10
}
