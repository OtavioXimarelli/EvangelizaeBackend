package org.evangelizae.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;



@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_HEADER = "X-Request-Id";

    static final String MDC_KEY = "requestId";

    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = resolveRequestId(request.getHeader(REQUEST_HEADER));
        response.setHeader(REQUEST_HEADER, requestId);


        try(MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, requestId)) {
            filterChain.doFilter(request, response);
        };


    }

    private String resolveRequestId(String providedRequestId) {
        if (providedRequestId != null && REQUEST_ID_PATTERN.matcher(providedRequestId).matches()) {
            return providedRequestId;
        }
        return UUID.randomUUID().toString();
    }
}
