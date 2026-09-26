package org.evangelizae.api.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.evangelizae.api.web.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.StringUtils;

import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalBearerAuthFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH = "/internal/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedToken;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InternalBearerAuthFilter(AppProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.expectedToken = properties.liturgy().importConfig().token();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            writeError(response, request, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORIZED", "A bearer token is required");
            return;
        }

        var providedToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(providedToken)) {
            writeError(response, request, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORIZED", "A bearer token is required");
            return;
        }

        if (!tokensMatch(expectedToken, providedToken)) {
            writeError(response, request, HttpServletResponse.SC_FORBIDDEN,
                    "FORBIDDEN", "The bearer token is invalid");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean tokensMatch(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(
                Instant.now(clock),
                status,
                HttpServletResponse.SC_UNAUTHORIZED == status ? "Unauthorized" : "Forbidden",
                code,
                message,
                request.getRequestURI()
        ));
    }
}
