package dev.aegis.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request a correlation ID ({@code X-Request-Id} if the caller sent a well-formed
 * one, otherwise a new UUID), logs it with every line (MDC {@code correlationId}) and returns it.
 * Ingress carries it through Kafka as the record ID, so one ID follows a message to the Checker.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        String id = given != null && SAFE.matcher(given).matches()
                ? given
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
