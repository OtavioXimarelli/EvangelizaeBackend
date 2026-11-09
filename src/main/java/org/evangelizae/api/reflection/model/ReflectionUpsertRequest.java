package org.evangelizae.api.reflection.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReflectionUpsertRequest(
        @NotBlank @Size(min = 1, max = 20000) String text,
        @NotBlank @Size(min = 1, max = 300) String attribution,
        @Size(max = 120) String reference
) {
}
