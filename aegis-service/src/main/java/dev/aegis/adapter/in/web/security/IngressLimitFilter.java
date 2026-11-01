package dev.aegis.adapter.in.web.security;

import dev.aegis.application.service.IngressLimits;
import dev.aegis.application.service.TokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-connection limits on the public endpoints ({@code /in/{connection}/...}), applied before the
 * body is read: a request rate (429) and a cap on requests in progress (503). Providers retry both,
 * so genuine messages are delayed, never lost; a flood on one connection cannot starve the others.
 */
@Component
public class IngressLimitFilter extends OncePerRequestFilter {

    private final IngressLimits limits;
    private final TokenBucket rate;
    private final Map<String, Semaphore> inProgress = new ConcurrentHashMap<>();
    private final MeterRegistry metrics;

    public IngressLimitFilter(IngressLimits limits, Clock clock, MeterRegistry metrics) {
        this.limits = limits;
        this.rate = new TokenBucket(limits.requestsPerMinute(), limits.burst(), clock);
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/in/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String connection = connection(request.getRequestURI());
        if (!rate.tryAcquire(connection)) {
            refuse(response, connection, 429, "rate");
            return;
        }
        Semaphore slots = inProgress.computeIfAbsent(connection, c -> new Semaphore(limits.concurrent()));
        if (!slots.tryAcquire()) {
            refuse(response, connection, 503, "concurrency");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    // "/in/razorpay/callback" → "razorpay". Unknown names are limited too, then rejected with 404.
    private static String connection(String uri) {
        String rest = uri.substring("/in/".length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private void refuse(HttpServletResponse response, String connection, int status, String limit) throws IOException {
        metrics.counter("aegis.ingress.limited", "limit", limit).increment();
        response.setStatus(status);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/problem+json");
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"status\":" + status + ",\"title\":\"Too many requests\"}");
    }
}
