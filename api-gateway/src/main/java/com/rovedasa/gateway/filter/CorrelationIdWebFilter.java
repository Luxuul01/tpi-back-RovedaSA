package com.rovedasa.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Asigna el X-Correlation-ID a cada request que entra al Gateway.
 *
 * Es un WebFilter con la máxima prioridad, así corre antes que la cadena de seguridad:
 * también las respuestas 401 y 403 que corta el Gateway llevan el header (Alcance, sección 10).
 * Si el cliente manda el header se respeta; si no, se genera uno con prefijo "req-".
 * El request que sigue hacia el microservicio lleva el mismo valor (trazabilidad T-01).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String ATRIBUTO_CORRELATION_ID = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId = (recibido == null || recibido.isBlank()) ? "req-" + UUID.randomUUID() : recibido;
        log.debug("Procesando request en Gateway con Correlation ID: {}", correlationId);

        ServerHttpRequest requestMutada = exchange.getRequest()
                .mutate()
                .headers(h -> h.set(CORRELATION_ID_HEADER, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        exchange.getAttributes().put(ATRIBUTO_CORRELATION_ID, correlationId);

        return chain.filter(exchange.mutate().request(requestMutada).build());
    }
}
