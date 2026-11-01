package dev.aegis.lab.gateway;

import org.springframework.http.HttpStatus;

/** An API error, rendered in Razorpay's error format. */
class GatewayException extends RuntimeException {

    private final HttpStatus status;

    GatewayException(HttpStatus status, String description) {
        super(description);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
