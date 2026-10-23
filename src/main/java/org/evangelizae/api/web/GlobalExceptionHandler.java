package org.evangelizae.api.web;

import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.evangelizae.api.liturgy.service.InvalidLiturgyImportException;
import org.evangelizae.api.liturgy.service.InvalidLiturgyRequestException;
import org.evangelizae.api.liturgy.service.LiturgyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.HtmlUtils;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) { this.clock = clock; }

    @ExceptionHandler(LiturgyUnavailableException.class)
    ResponseEntity<ApiError> handleUnavailable(LiturgyUnavailableException exception,
            HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LITURGY_UNAVAILABLE", exception.getMessage(), request);
    }

    // Driver details (host, user, cause) stay in the logs, never in the response.
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ApiError> handleStorageUnavailable(DataAccessResourceFailureException exception,
            HttpServletRequest request) {
        log.error("Storage unavailable", exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                "Storage is temporarily unavailable", request);
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

        String safeCode = code != null ? HtmlUtils.htmlEscape(code, "UTF-8") : null;
        String safeMessage = message != null ? HtmlUtils.htmlEscape(message, "UTF-8") : null;
        String safeUri = HtmlUtils.htmlEscape(request.getRequestURI(), "UTF-8");

        ApiError apiError = new ApiError(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                safeCode,
                safeMessage,
                safeUri
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(apiError);
    }
}
