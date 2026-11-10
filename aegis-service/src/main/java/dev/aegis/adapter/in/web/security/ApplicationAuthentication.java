package dev.aegis.adapter.in.web.security;

import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.cert.X509Certificate;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates applications on the Gateway API (LLD §4 step 1): {@code Authorization: Bearer
 * <api key>}, and the client certificate's subject when the application requires one. The caller's
 * name is then available to the controller; whether it may use a connection is the use case's call.
 */
@Component
public class ApplicationAuthentication implements HandlerInterceptor {

    /** Request attribute holding the authenticated application's name. */
    public static final String CALLER = "aegis.caller";

    private static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";

    private final DirectoryPort directory;

    public ApplicationAuthentication(DirectoryPort directory) {
        this.directory = directory;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String header = request.getHeader("Authorization");
        Optional<Application> caller = header != null && header.startsWith("Bearer ")
                ? directory.applicationByKey(header.substring(7).trim())
                : Optional.empty();
        if (caller.isEmpty()) {
            return refuse(response, HttpStatus.UNAUTHORIZED, "missing or unknown application key");
        }
        String required = caller.get().clientCertSubject();
        if (required != null
                && !certificateSubject(request).map(required::equals).orElse(false)) {
            return refuse(response, HttpStatus.FORBIDDEN, "client certificate does not match the application");
        }
        request.setAttribute(CALLER, caller.get().name());
        return true;
    }

    /** The verified client certificate's subject, when the TLS terminator passed one on. */
    public static Optional<String> certificateSubject(HttpServletRequest request) {
        if (request.getAttribute(CERTIFICATES) instanceof X509Certificate[] chain && chain.length > 0) {
            return Optional.of(chain[0].getSubjectX500Principal().getName());
        }
        return Optional.empty();
    }

    private static boolean refuse(HttpServletResponse response, HttpStatus status, String detail)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                        + status.value() + ",\"detail\":\"" + detail + "\"}");
        return false;
    }
}
