package org.evangelizae.api.reflection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.evangelizae.api.reflection.model.DailyReflection;
import org.evangelizae.api.reflection.model.ReflectionUpsertRequest;
import org.evangelizae.api.reflection.model.ReflectionsListResponse;
import org.evangelizae.api.reflection.service.InvalidReflectionRequestException;
import org.evangelizae.api.reflection.service.ReflectionNotFoundException;
import org.evangelizae.api.reflection.service.ReflectionService;
import org.evangelizae.api.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReflectionAdminController.class)
@Import(GlobalExceptionHandler.class)
class ReflectionAdminControllerTest {

    private static final String TOKEN = "test-import-token-with-at-least-32-chars-1234567890";
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReflectionService reflectionService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void rejectsRequestsWithoutABearerToken() throws Exception {
        mockMvc.perform(get("/internal/v1/reflections")
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-30"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsRequestsWithAnInvalidBearerToken() throws Exception {
        mockMvc.perform(put("/internal/v1/reflections/2026-09-24")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void upsertsAReflection() throws Exception {
        var reflection = new DailyReflection(
                LocalDate.parse("2026-09-24"), "Texto", "Autor", "Ref", NOW, NOW);
        when(reflectionService.upsert(eq(LocalDate.parse("2026-09-24")), any(ReflectionUpsertRequest.class)))
                .thenReturn(reflection);

        mockMvc.perform(put("/internal/v1/reflections/2026-09-24")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-09-24"))
                .andExpect(jsonPath("$.text").value("Texto"))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.updatedAt").value(NOW.toString()));
    }

    @Test
    void rejectsBlankReflectionContent() throws Exception {
        mockMvc.perform(put("/internal/v1/reflections/2026-09-24")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"","attribution":"Autor"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFLECTION_INVALID"));
    }

    @Test
    void listsReflectionsInRange() throws Exception {
        var reflection = new DailyReflection(
                LocalDate.parse("2026-09-24"), "Texto", "Autor", null, NOW, NOW);
        when(reflectionService.list(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")))
                .thenReturn(new ReflectionsListResponse(List.of(reflection)));

        mockMvc.perform(get("/internal/v1/reflections")
                        .header("Authorization", "Bearer " + TOKEN)
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reflections[0].date").value("2026-09-24"));
    }

    @Test
    void rejectsInvalidRanges() throws Exception {
        when(reflectionService.list(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-01")))
                .thenThrow(new InvalidReflectionRequestException("from must not be after to"));
        when(reflectionService.list(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
                .thenThrow(new InvalidReflectionRequestException("date range must not exceed 62 days"));

        mockMvc.perform(get("/internal/v1/reflections")
                        .header("Authorization", "Bearer " + TOKEN)
                        .queryParam("from", "2026-09-30")
                        .queryParam("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFLECTION_INVALID_RANGE"));

        mockMvc.perform(get("/internal/v1/reflections")
                        .header("Authorization", "Bearer " + TOKEN)
                        .queryParam("from", "2026-01-01")
                        .queryParam("to", "2026-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFLECTION_INVALID_RANGE"));
    }

    @Test
    void deletesAnExistingReflection() throws Exception {
        mockMvc.perform(delete("/internal/v1/reflections/2026-09-24")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsNotFoundWhenDeletingAnAbsentReflection() throws Exception {
        doThrow(new ReflectionNotFoundException("No reflection for 2026-09-24"))
                .when(reflectionService).delete(LocalDate.parse("2026-09-24"));

        mockMvc.perform(delete("/internal/v1/reflections/2026-09-24")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFLECTION_NOT_FOUND"));
    }

    private static String validPayload() {
        return """
                {
                  "text": "Texto da reflexão",
                  "attribution": "Autor",
                  "reference": "Mt 25,1-13"
                }
                """;
    }
}
