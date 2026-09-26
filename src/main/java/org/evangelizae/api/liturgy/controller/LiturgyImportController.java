package org.evangelizae.api.liturgy.controller;

import org.evangelizae.api.liturgy.model.LiturgyImportRequest;
import org.evangelizae.api.liturgy.model.LiturgyImportResponse;
import org.evangelizae.api.liturgy.service.LiturgyService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping(path = "/internal/v1/liturgy", produces = MediaType.APPLICATION_JSON_VALUE)
public class LiturgyImportController {

    private final LiturgyService liturgyService;

    public LiturgyImportController(LiturgyService liturgyService) {
        this.liturgyService = liturgyService;
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LiturgyImportResponse importBatch(@Valid @RequestBody LiturgyImportRequest request) {
        return liturgyService.importBatch(request);
    }
}
