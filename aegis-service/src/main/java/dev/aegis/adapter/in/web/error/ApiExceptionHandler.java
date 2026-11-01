package dev.aegis.adapter.in.web.error;

import dev.aegis.adapter.in.web.dto.InboundRequest;
import dev.aegis.application.port.in.BadRequestException;
import dev.aegis.application.port.in.ForbiddenException;
import dev.aegis.application.port.in.UnavailableException;
import dev.aegis.application.port.in.UnknownConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error of the HTTP API as an RFC 9457 problem, in one place. Messages never echo request
 * content or secrets.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnknownConnectionException.class)
    ProblemDetail unknownConnection(UnknownConnectionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "unknown connection");
    }

    @ExceptionHandler(UnavailableException.class)
    ProblemDetail unavailable(UnavailableException e) {
        log.error("unavailable: {}", e.getMessage(), e.getCause());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "temporarily unavailable, retry");
    }

    @ExceptionHandler(InboundRequest.BodyTooLargeException.class)
    ProblemDetail tooLarge(InboundRequest.BodyTooLargeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "this connection belongs to another application");
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
