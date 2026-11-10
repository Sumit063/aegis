package dev.aegis.adapter.in.web.security;

import dev.aegis.application.port.out.DirectoryPort;
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
 * Per-caller limits on the public endpoints, applied before the body is read: a request rate (429)
 * and a cap on requests in progress (503). The caller is the connection of {@code /in/{connection}/...}
 * or the partner named on {@code /partner/...}; every name that is not configured shares one key, so
 * invented names cannot grow the maps. Providers retry both, so genuine messages are delayed, never
 * lost; a flood on one connection or partner cannot starve the others. A partner's own rate is
 * applied after it is authenticated (LLD §14).
 */
@Component
public class IngressLimitFilter extends OncePerRequestFilter {

    private final IngressLimits limits;
    private final TokenBucket rate;
    private final Map<String, Semaphore> inProgress = new ConcurrentHashMap<>();
    private final MeterRegistry metrics;
    private final DirectoryPort directory;

    public IngressLimitFilter(IngressLimits limits, Clock clock, MeterRegistry metrics, DirectoryPort directory) {
        this.limits = limits;
        this.directory = directory;
        this.rate = new TokenBucket(limits.requestsPerMinute(), limits.burst(), clock);
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/in/") && !uri.startsWith("/partner/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String connection = caller(request);
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

    // "/in/razorpay/callback" → "razorpay"; a partner → "partner:<name>". Unknown names share "?" and
    // "partner:?": limited together, then rejected (404, 401).
    private String caller(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/partner/")) {
            String partner = request.getHeader("X-Partner-Id");
            return partner != null && directory.partner(partner).isPresent() ? "partner:" + partner : "partner:?";
        }
        String rest = uri.substring("/in/".length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        return directory.connection(name).isPresent() ? name : "?";
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
