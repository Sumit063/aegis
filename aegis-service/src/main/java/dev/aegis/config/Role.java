package dev.aegis.config;

/** A role this process runs. One deployable, started with any combination of roles (HLD §5). */
public enum Role {
    /** Public webhook endpoint: authenticate, write to the claim log, acknowledge. */
    INGRESS,
    /** Internal checkout-verification API (Route A). */
    VERIFY,
    /** Claim consumer, confirmation and recheck workers. */
    PROCESSOR,
    /** Delivery worker: signed notifications to merchant backends. */
    DELIVERER,
    /** Internal investigation and operations API. */
    ADMIN
}
