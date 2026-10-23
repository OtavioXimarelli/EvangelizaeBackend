package dev.ximarelli.rosary.backend.prayers;

import jakarta.validation.constraints.Size;

public record MarkAnsweredRequest(
        @Size(max = 2000) String testimonial) {
}
