package dev.aegis.adapter.in.web.controller;

import dev.aegis.adapter.in.web.dto.InboundRequest;
import dev.aegis.application.port.in.HandleCallbackUseCase;
import dev.aegis.application.port.in.ReceiveProviderMessageUseCase;
import dev.aegis.application.port.in.ReceiveProviderMessageUseCase.Receipt;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import dev.aegis.provider.spi.Channel;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public provider endpoints (LLD §2): {@code POST /in/{connection}} for webhooks and asynchronous
 * responses, answered with the provider's acknowledgement only after the message is stored (a
 * forgery gets 401 and nothing else); {@code POST /in/{connection}/callback} for Checkout results
 * carried by the browser, answered with a redirect to the application's return page.
 */
@RestController
@ConditionalOnRole(Role.INGRESS)
class IngressController {

    private final ReceiveProviderMessageUseCase ingress;
    private final HandleCallbackUseCase callbacks;

    IngressController(ReceiveProviderMessageUseCase ingress, HandleCallbackUseCase callbacks) {
        this.ingress = ingress;
        this.callbacks = callbacks;
    }

    @PostMapping("/in/{connection}")
    ResponseEntity<byte[]> receive(@PathVariable String connection, HttpServletRequest request) {
        Receipt receipt = ingress.receive(
                connection, Channel.SERVER, InboundRequest.read(request).message());
        if (!receipt.verified()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(receipt.ack().status())
                .contentType(MediaType.parseMediaType(receipt.ack().contentType()))
                .body(receipt.ack().body());
    }

    @PostMapping("/in/{connection}/callback")
    ResponseEntity<Void> callback(@PathVariable String connection, HttpServletRequest request) {
        URI next = callbacks.handle(connection, InboundRequest.read(request).message());
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(next).build();
    }
}
