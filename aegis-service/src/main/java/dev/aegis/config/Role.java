package dev.aegis.config;

/** A role this process runs. One deployable, started with any combination of roles (HLD §7). */
public enum Role {
    /** Public provider endpoints: transport and signature checks, write to Kafka, acknowledge. */
    INGRESS,
    /** Kafka consumer: inbound checks and verdicts; also serves browser callbacks. */
    CHECKER,
    /** Internal API for application-initiated provider calls: validations, payments, status checks. */
    GATEWAY,
    /** Delivers passed messages to applications over mTLS, signed, with retries. */
    FORWARDER,
    /** Internal investigation and operations API. */
    ADMIN,
    /** Public partner API: authenticates partners, holds them to their grants and rates, proxies once. */
    PARTNER
}
