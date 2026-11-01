package dev.aegis.adapter.in.web.controller;

import dev.aegis.adapter.in.web.dto.InboundRequest;
import dev.aegis.adapter.in.web.security.ApplicationAuthentication;
import dev.aegis.application.port.in.GatewayUseCase;
import dev.aegis.application.port.in.GatewayUseCase.Answer;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import dev.aegis.provider.spi.CallKind;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway API (LLD §2, §4): the application sends the provider-native request it would have sent
 * itself; it gets the provider's reply unchanged plus {@code X-Aegis-Verdict}, or Aegis's refusal
 * (409, 429) when the call must not go out.
 */
@RestController
@ConditionalOnRole(Role.GATEWAY)
class GatewayController {

    static final String VERDICT = "X-Aegis-Verdict";

    private final GatewayUseCase gateway;

    GatewayController(GatewayUseCase gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/gw/{connection}/validations")
    ResponseEntity<byte[]> validation(@PathVariable String connection, HttpServletRequest request) {
        return call(connection, CallKind.VALIDATION, request);
    }

    @PostMapping("/gw/{connection}/payments")
    ResponseEntity<byte[]> payment(@PathVariable String connection, HttpServletRequest request) {
        return call(connection, CallKind.PAY_REQUEST, request);
    }

    @PostMapping("/gw/{connection}/status")
    ResponseEntity<byte[]> status(@PathVariable String connection, HttpServletRequest request) {
        return call(connection, CallKind.STATUS_CHECK, request);
    }

    private ResponseEntity<byte[]> call(String connection, CallKind kind, HttpServletRequest request) {
        String caller = (String) request.getAttribute(ApplicationAuthentication.CALLER);
        Answer answer = gateway.call(
                caller, connection, kind, InboundRequest.read(request).message());
        return ResponseEntity.status(answer.status())
                .header(VERDICT, answer.verdict().toString())
                .contentType(MediaType.parseMediaType(answer.contentType()))
                .body(answer.body());
    }
}
