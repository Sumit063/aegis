package dev.aegis.application.service;

/**
 * Bounds on what unauthenticated traffic can cost (HARDENING §3, §8), per connection and instance.
 *
 * @param requestsPerMinute requests accepted on {@code /in/{connection}} before 429 (providers retry)
 * @param burst short bursts allowed above the steady rate
 * @param concurrent requests in progress before 503
 * @param forgeriesPerMinute unverified messages kept as evidence; beyond this they are only counted
 */
public record IngressLimits(int requestsPerMinute, int burst, int concurrent, int forgeriesPerMinute) {

    public static final IngressLimits DEFAULTS = new IngressLimits(6_000, 500, 64, 60);
}
