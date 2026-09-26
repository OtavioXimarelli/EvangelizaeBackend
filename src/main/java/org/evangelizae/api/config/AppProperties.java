package org.evangelizae.api.config;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Cors cors,
        @Valid @NotNull Liturgy liturgy
) {
    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {}

    public record Liturgy(@Valid @NotNull Import importConfig) {
        public record Import(
                @NotBlank @Size(min = 32, message = "import token must be at least 32 characters") String token) {}
    }
}
