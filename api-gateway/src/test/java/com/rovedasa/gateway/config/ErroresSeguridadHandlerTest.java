package com.rovedasa.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rovedasa.gateway.filter.CorrelationIdWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ErroresSeguridadHandlerTest {

    private final ErroresSeguridadHandler handler = new ErroresSeguridadHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private MockServerWebExchange exchange(String metodo, String path) {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(metodo), path).build());
        ex.getAttributes().put(CorrelationIdWebFilter.ATRIBUTO_CORRELATION_ID, "req-test-123");
        return ex;
    }

    @Test
    @DisplayName("401 sin token: cuerpo ApiError y X-Correlation-ID")
    void sinTokenDevuelve401ConApiError() throws Exception {
        MockServerWebExchange ex = exchange("GET", "/api/contratos");

        handler.commence(ex, new BadCredentialsException("sin token")).block();

        JsonNode body = mapper.readTree(ex.getResponse().getBodyAsString().block());
        assertAll(
                () -> assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode()),
                () -> assertEquals(MediaType.APPLICATION_JSON, ex.getResponse().getHeaders().getContentType()),
                () -> assertEquals("req-test-123", ex.getResponse().getHeaders().getFirst("X-Correlation-ID")),
                () -> assertEquals(401, body.get("status").asInt()),
                () -> assertEquals("Unauthorized", body.get("error").asText()),
                () -> assertEquals("/api/contratos", body.get("path").asText()),
                () -> assertEquals("req-test-123", body.get("correlationId").asText()));
    }

    @Test
    @DisplayName("403 por rol: cuerpo ApiError y X-Correlation-ID")
    void rolSinPermisoDevuelve403ConApiError() throws Exception {
        MockServerWebExchange ex = exchange("POST", "/api/propiedades");

        handler.handle(ex, new AccessDeniedException("rol")).block();

        JsonNode body = mapper.readTree(ex.getResponse().getBodyAsString().block());
        assertAll(
                () -> assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode()),
                () -> assertEquals(403, body.get("status").asInt()),
                () -> assertEquals("Forbidden", body.get("error").asText()),
                () -> assertEquals("req-test-123", body.get("correlationId").asText()));
    }
}
