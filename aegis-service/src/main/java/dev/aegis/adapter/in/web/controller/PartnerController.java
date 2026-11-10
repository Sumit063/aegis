package dev.aegis.adapter.in.web.controller;

import dev.aegis.adapter.in.web.dto.InboundRequest;
import dev.aegis.adapter.in.web.security.ApplicationAuthentication;
import dev.aegis.application.port.in.PartnerUseCase;
import dev.aegis.application.port.in.PartnerUseCase.Answer;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import dev.aegis.domain.partner.PartnerRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner API (LLD §2, §14): {@code /partner/<operation>/…} with {@code X-Partner-Id},
 * {@code X-Request-Id}, {@code X-Timestamp} and {@code X-Signature}. The application's answer comes
 * back unchanged; {@code X-Aegis-Replayed: true} marks the stored answer of an earlier execution.
 */
@RestController
@ConditionalOnRole(Role.PARTNER)
class PartnerController {

    static final String PREFIX = "/partner";

    private final PartnerUseCase partners;

    PartnerController(PartnerUseCase partners) {
        this.partners = partners;
    }

    @RequestMapping(
            path = PREFIX + "/**",
            method = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.PATCH,
                RequestMethod.DELETE
            })
    ResponseEntity<byte[]> call(HttpServletRequest request) {
        // The raw path, as the partner signed it; never a decoded or normalised one.
        String query = request.getQueryString();
        String path = request.getRequestURI().substring(PREFIX.length()) + (query == null ? "" : "?" + query);
        String requestId = request.getHeader("X-Request-Id");
        Answer answer = partners.call(new PartnerUseCase.Call(
                request.getHeader("X-Partner-Id"),
                requestId,
                request.getHeader("X-Timestamp"),
                request.getHeader("X-Signature"),
                ApplicationAuthentication.certificateSubject(request).orElse(null),
                request.getMethod(),
                path,
                request.getContentType(),
                InboundRequest.read(request).message().body()));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(answer.status());
        if (answer.contentType() != null) {
            response.contentType(MediaType.parseMediaType(answer.contentType()));
        }
        if (requestId != null && PartnerRequest.REQUEST_ID.matcher(requestId).matches()) {
            response.header("X-Aegis-Request-Id", requestId);
        }
        if (answer.replayed()) {
            response.header("X-Aegis-Replayed", "true");
        }
        if (answer.retryAfter() != null) {
            response.header(
                    "Retry-After",
                    String.valueOf(Math.max(1, (answer.retryAfter().toMillis() + 999) / 1000)));
        }
        return response.body(answer.body());
    }
}
