package dev.aegis.lab.gateway;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders every API error in Razorpay's format: {@code {"error": {"code", "description"}}}. */
@RestControllerAdvice
class ErrorHandler {

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<Map<String, Object>> error(GatewayException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", Map.of("code", "BAD_REQUEST_ERROR", "description", e.getMessage())));
    }
}
