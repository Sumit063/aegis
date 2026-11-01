package dev.aegis.adapter.in.web.security;

import dev.aegis.config.AdminProperties;
import dev.aegis.provider.spi.Secret;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** {@code /admin/**} requires {@code Authorization: Bearer <admin key>} (constant-time comparison). */
@Component
public class AdminAuthentication implements HandlerInterceptor {

    /** Who made a change, for the settings audit trail. */
    public static final String OPERATOR = "admin";

    private final Secret key;

    public AdminAuthentication(AdminProperties properties) {
        this.key = new Secret(properties.apiKey());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String header = request.getHeader("Authorization");
        if (header != null
                && header.startsWith("Bearer ")
                && key.matches(header.substring(7).trim())) {
            return true;
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");
        return false;
    }
}
