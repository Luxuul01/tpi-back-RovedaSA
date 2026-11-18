package com.rovedasa.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Asigna el X-Correlation-ID a cada request que entra al Gateway.
 *
 * Es un WebFilter con la máxima prioridad, así corre antes que la cadena de seguridad:
 * también las respuestas 401 y 403 que corta el Gateway llevan el header (Alcance, sección 10).
 * Si el cliente manda uno con formato válido ({@code [A-Za-z0-9._-]{1,64}}) se respeta; si no viene o no
 * cumple (saltos de línea, HTML, miles de caracteres) se genera uno con prefijo "req-".
 * El request que sigue hacia el microservicio lleva el mismo valor (trazabilidad T-01).
 *
 * El header de la respuesta se pone justo antes de enviarla (beforeCommit) y reemplaza al que copia el
 * Gateway desde la respuesta del microservicio: así sale una sola vez, también en los errores del propio Gateway.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String ATRIBUTO_CORRELATION_ID = "correlationId";

    private static final Pattern FORMATO_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = sanitizar(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));
        log.debug("Procesando request en Gateway con Correlation ID: {}", correlationId);

        ServerHttpRequest requestMutada = exchange.getRequest()
                .mutate()
                .headers(h -> h.set(CORRELATION_ID_HEADER, correlationId))
                .build();
        exchange.getAttributes().put(ATRIBUTO_CORRELATION_ID, correlationId);

        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(requestMutada).build());
    }

    /**
     * Devuelve el ID recibido si cumple el formato permitido, o uno nuevo si no viene o no lo cumple.
     */
    static String sanitizar(String recibido) {
        if (recibido != null && FORMATO_VALIDO.matcher(recibido).matches()) {
            return recibido;
        }
        if (recibido != null && !recibido.isEmpty()) {
            // No se loguea el valor recibido: justamente puede traer saltos de línea o basura
            log.debug("X-Correlation-ID con formato inválido (largo {}); se genera uno nuevo", recibido.length());
        }
        return "req-" + UUID.randomUUID();
    }
}
