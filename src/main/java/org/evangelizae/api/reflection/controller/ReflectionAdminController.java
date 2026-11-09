package org.evangelizae.api.reflection.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.evangelizae.api.reflection.model.DailyReflection;
import org.evangelizae.api.reflection.model.ReflectionUpsertRequest;
import org.evangelizae.api.reflection.model.ReflectionsListResponse;
import org.evangelizae.api.reflection.service.ReflectionService;
import org.evangelizae.api.web.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping("/internal/v1/reflections")
public class ReflectionAdminController {

    private final ReflectionService reflectionService;
    private final Clock clock;

    public ReflectionAdminController(ReflectionService reflectionService, Clock clock) {
        this.reflectionService = reflectionService;
        this.clock = clock;
    }

    @GetMapping
    ReflectionsListResponse list(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reflectionService.list(from, to);
    }

    @PutMapping("/{date}")
    DailyReflection upsert(@PathVariable LocalDate date, @Valid @RequestBody ReflectionUpsertRequest request) {
        return reflectionService.upsert(date, request);
    }

    @DeleteMapping("/{date}")
    ResponseEntity<Void> delete(@PathVariable LocalDate date) {
        reflectionService.delete(date);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(
                        Instant.now(clock),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        HttpStatus.UNPROCESSABLE_CONTENT.getReasonPhrase(),
                        "REFLECTION_INVALID",
                        HtmlUtils.htmlEscape("text and attribution are required", "UTF-8"),
                        HtmlUtils.htmlEscape(request.getRequestURI(), "UTF-8")
                ));
    }
}
