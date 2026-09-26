package org.evangelizae.api.liturgy.controller;

import org.evangelizae.api.liturgy.model.DailyLiturgy;
import org.evangelizae.api.liturgy.service.LiturgyService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/liturgy", produces = MediaType.APPLICATION_JSON_VALUE)
public class LiturgyController {

    private final LiturgyService liturgyService;

    public LiturgyController(LiturgyService liturgyService) {
        this.liturgyService = liturgyService;
    }

    @GetMapping("/today")
    public DailyLiturgy getToday(
            @RequestParam String timezone,
            @RequestParam String locale
    ) {
        return liturgyService.getToday(timezone, locale);
    }
}
