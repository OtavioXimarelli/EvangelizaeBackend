package org.evangelizae.api.liturgy.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;

import org.evangelizae.api.liturgy.service.InvalidLiturgyRequestException;
import org.evangelizae.api.liturgy.service.LiturgyService;
import org.evangelizae.api.liturgy.service.LiturgyUnavailableException;
import org.evangelizae.api.web.HealthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {LiturgyController.class, HealthController.class})
class LiturgyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiturgyService liturgyService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-24T02:00:00Z"));
    }

    @Test
    void returnsServiceUnavailableWhenLiturgyIsMissing() throws Exception {
        when(liturgyService.getToday("America/Sao_Paulo", "pt-BR"))
                .thenThrow(new LiturgyUnavailableException(
                        "A liturgia não está disponível para o dia de 2026-09-24"));

        mockMvc.perform(get("/api/v1/liturgy/today")
                        .queryParam("timezone", "America/Sao_Paulo")
                        .queryParam("locale", "pt-BR"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LITURGY_UNAVAILABLE"))
                .andExpect(jsonPath("$.path").value("/api/v1/liturgy/today"));
    }

    @Test
    void validatesTheLocale() throws Exception {
        when(liturgyService.getToday("America/Sao_Paulo", "en-US"))
                .thenThrow(new InvalidLiturgyRequestException("locale must be pt-BR"));

        mockMvc.perform(get("/api/v1/liturgy/today")
                        .queryParam("timezone", "America/Sao_Paulo")
                        .queryParam("locale", "en-US"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorIsNotExposedUnderApiPrefix() throws Exception {
        mockMvc.perform(get("/api/v1/actuator/health"))
                .andExpect(status().isNotFound());
    }
}

