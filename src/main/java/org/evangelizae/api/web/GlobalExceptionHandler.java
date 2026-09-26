package org.evangelizae.api.web;

import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.evangelizae.api.liturgy.service.InvalidLiturgyImportException;
import org.evangelizae.api.liturgy.service.InvalidLiturgyRequestException;
import org.evangelizae.api.liturgy.service.LiturgyUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) { this.clock = clock; }

    @ExceptionHandler(LiturgyUnavailableException.class)
    ResponseEntity<ApiError> handleUnavailable(LiturgyUnavailableException exception,
            HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LITURGY_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler({InvalidLiturgyRequestException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleInvalidImport(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_IMPORT", "Import request validation failed", request);
    }

    @ExceptionHandler(InvalidLiturgyImportException.class)
    ResponseEntity<ApiError> handleInvalidImport(InvalidLiturgyImportException exception,
            HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_LITURGY_IMPORT",
                exception.getMessage(), request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(clock), status.value(),
                status.getReasonPhrase(), code, message, request.getRequestURI()));
    }
}
