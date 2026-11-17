package com.rovedasa.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filtro = new CorrelationIdWebFilter();

    @Test
    @DisplayName("Respeta el X-Correlation-ID del cliente y lo propaga al microservicio")
    void respetaElHeaderDelCliente() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/personas").header("X-Correlation-ID", "abc-123").build());
        AtomicReference<ServerWebExchange> siguiente = new AtomicReference<>();

        filtro.filter(ex, e -> { siguiente.set(e); return Mono.empty(); }).block();

        assertAll(
                () -> assertEquals("abc-123", ex.getResponse().getHeaders().getFirst("X-Correlation-ID")),
                () -> assertEquals("abc-123", siguiente.get().getRequest().getHeaders().getFirst("X-Correlation-ID")),
                () -> assertEquals(1, siguiente.get().getRequest().getHeaders().get("X-Correlation-ID").size()),
                () -> assertEquals("abc-123", ex.getAttributes().get(CorrelationIdWebFilter.ATRIBUTO_CORRELATION_ID)));
    }

    @Test
    @DisplayName("Si el cliente no lo manda, genera uno con prefijo req-")
    void generaUnoSiNoViene() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/personas").build());

        filtro.filter(ex, e -> Mono.empty()).block();

        String generado = ex.getResponse().getHeaders().getFirst("X-Correlation-ID");
        assertTrue(generado != null && generado.startsWith("req-"), generado);
    }
}
