package org.evangelizae.api.liturgy.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.evangelizae.api.liturgy.model.LiturgyImportResponse;
import org.evangelizae.api.liturgy.service.InvalidLiturgyImportException;
import org.evangelizae.api.liturgy.service.LiturgyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiturgyImportController.class)
class LiturgyImportControllerTest {

    private static final String TOKEN = "test-import-token-with-at-least-32-chars-1234567890";
    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiturgyService liturgyService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-24T02:00:00Z"));
    }

    @Test
    void rejectsRequestsWithoutABearerToken() throws Exception {
        mockMvc.perform(post("/internal/v1/liturgy/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsRequestsWithAnInvalidBearerToken() throws Exception {
        mockMvc.perform(post("/internal/v1/liturgy/import")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void importsAValidBatch() throws Exception {
        when(liturgyService.importBatch(any())).thenReturn(new LiturgyImportResponse(
                UUID.fromString("0199d5f0-0000-7000-8000-000000000001"),
                LiturgyImportResponse.Status.SUCCESS,
                1,
                1,
                1,
                0,
                0,
                List.of()));

        mockMvc.perform(post("/internal/v1/liturgy/import")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void rejectsMalformedBatches() throws Exception {
        mockMvc.perform(post("/internal/v1/liturgy/import")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
    }

    @Test
    void rejectsInvalidLiturgyContent() throws Exception {
        when(liturgyService.importBatch(any()))
                .thenThrow(new InvalidLiturgyImportException("Invalid content"));

        mockMvc.perform(post("/internal/v1/liturgy/import")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_LITURGY_IMPORT"));
    }

    private static String validPayload() {
        return """
                {
                  "schemaVersion": "1.0",
                  "scraperVersion": "0.1.0",
                  "scrapedAt": "2026-09-24T01:00:00Z",
                  "period": {
                    "from": "2026-09-24",
                    "to": "2026-09-24"
                  },
                  "days": [
                    {
                      "date": "2026-09-24",
                      "celebration": {
                        "name": "Quarta-feira",
                        "type": "WEEKDAY",
                        "liturgicalColor": "GREEN"
                      },
                      "liturgicalSeason": {
                        "name": "Tempo Comum",
                        "week": 25,
                        "liturgicalYear": "A"
                      },
                      "parts": {
                        "readings": [
                          {
                            "type": "GOSPEL",
                            "reference": "Mt 11,11-15",
                            "title": "Evangelho",
                            "text": "Texto do Evangelho"
                          }
                        ]
                      },
                      "sources": [
                        {
                          "name": "CNBB",
                          "role": "PRIMARY",
                          "collectedAt": "2026-09-24T01:00:00Z",
                          "contentHash": "%s"
                        }
                      ],
                      "validation": {
                        "status": "VALID",
                        "sourcesCompared": 1,
                        "warnings": []
                      }
                    }
                  ]
                }
                """.formatted(CONTENT_HASH);
    }
}
