package dev.aegis.adapter.in.web.controller;

import dev.aegis.adapter.in.web.dto.PaymentDto;
import dev.aegis.adapter.in.web.dto.SettingsDto;
import dev.aegis.adapter.in.web.dto.ValidationDto;
import dev.aegis.adapter.in.web.security.AdminAuthentication;
import dev.aegis.application.port.in.AdminUseCase;
import dev.aegis.application.port.in.SettingsUseCase;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API (LLD §2): look up by reference, list blocked messages, re-forward, runtime settings. */
@RestController
@RequestMapping("/admin")
@ConditionalOnRole(Role.ADMIN)
class AdminController {

    private final AdminUseCase admin;
    private final SettingsUseCase settings;

    AdminController(AdminUseCase admin, SettingsUseCase settings) {
        this.admin = admin;
        this.settings = settings;
    }

    @GetMapping("/payments/{id}")
    ResponseEntity<PaymentDto> payment(@PathVariable long id) {
        return ResponseEntity.of(admin.payment(id).map(PaymentDto::of));
    }

    /** Any reference: payment ID, order or bill reference, or the application's own reference. */
    @GetMapping("/payments")
    List<PaymentDto> payments(@RequestParam String ref) {
        return admin.paymentsByRef(ref).stream().map(PaymentDto::of).toList();
    }

    @GetMapping("/validations")
    List<ValidationDto> validations(@RequestParam String ref) {
        return admin.validationsByRef(ref).stream().map(ValidationDto::of).toList();
    }

    @GetMapping("/blocked")
    List<PaymentDto> blocked(
            @RequestParam(required = false) Instant since, @RequestParam(defaultValue = "100") int limit) {
        Instant from = since == null ? Instant.now().minus(Duration.ofDays(1)) : since;
        return admin.blockedSince(from, limit).stream().map(PaymentDto::of).toList();
    }

    @PostMapping("/payments/{id}/messages/{n}/forward")
    ResponseEntity<Void> reforward(@PathVariable long id, @PathVariable int n) {
        admin.reforward(id, n);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/settings")
    SettingsDto settings() {
        return SettingsDto.of(settings.current());
    }

    @PutMapping("/settings")
    SettingsDto changeSettings(@RequestBody SettingsDto body) {
        return SettingsDto.of(settings.update(body.toSettings(), AdminAuthentication.OPERATOR));
    }
}
