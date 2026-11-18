package com.rovedasa.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
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

        filtro.filter(ex, e -> { siguiente.set(e); return e.getResponse().setComplete(); }).block();

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

        filtro.filter(ex, e -> e.getResponse().setComplete()).block();

        String generado = ex.getResponse().getHeaders().getFirst("X-Correlation-ID");
        assertTrue(generado != null && generado.startsWith("req-"), generado);
    }

    @Test
    @DisplayName("Si la respuesta del microservicio ya trae el header (lo copia el ruteo con addAll), sale una sola vez")
    void noDuplicaElHeaderQueYaTraeLaRespuestaDelMicroservicio() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/personas").header("X-Correlation-ID", "abc-123").build());

        // Simula al NettyRoutingFilter: copia los headers de la respuesta del servicio con addAll
        filtro.filter(ex, e -> {
            e.getResponse().getHeaders().add("X-Correlation-ID", "abc-123");
            return e.getResponse().setComplete();
        }).block();

        assertEquals(List.of("abc-123"), ex.getResponse().getHeaders().get("X-Correlation-ID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "abc\tdef", "con espacios", "ñandú",
            // 65 caracteres: uno más que el máximo
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    @DisplayName("Un X-Correlation-ID con formato inválido se reemplaza (no se refleja ni se propaga)")
    void reemplazaUnCorrelationIdInvalido(String recibido) {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/personas").header("X-Correlation-ID", recibido).build());
        AtomicReference<ServerWebExchange> siguiente = new AtomicReference<>();

        filtro.filter(ex, e -> { siguiente.set(e); return e.getResponse().setComplete(); }).block();

        String devuelto = ex.getResponse().getHeaders().getFirst("X-Correlation-ID");
        assertAll(
                () -> assertTrue(devuelto.startsWith("req-"), devuelto),
                () -> assertEquals(devuelto, siguiente.get().getRequest().getHeaders().getFirst("X-Correlation-ID")),
                () -> assertEquals(devuelto, ex.getAttributes().get(CorrelationIdWebFilter.ATRIBUTO_CORRELATION_ID)));
    }

    @Test
    @DisplayName("Acepta hasta 64 caracteres con letras, números, punto, guion y guion bajo")
    void aceptaElMaximoPermitido() {
        String valido = "a.b_c-" + "9".repeat(58);

        assertEquals(64, valido.length());
        assertEquals(valido, CorrelationIdWebFilter.sanitizar(valido));
    }
}
