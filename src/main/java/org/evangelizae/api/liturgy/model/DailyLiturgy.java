package org.evangelizae.api.liturgy.model;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record DailyLiturgy(
        LocalDate date,
        String title,
        LiturgicalColor color,
        LiturgyPrayers prayers,
        List<LiturgyGroup> groups,
        LiturgySource source,
        @JsonInclude(JsonInclude.Include.NON_NULL) LiturgyReflection reflection
) {
}

