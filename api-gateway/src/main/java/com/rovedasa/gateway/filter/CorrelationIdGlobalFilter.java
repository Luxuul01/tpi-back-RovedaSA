package com.rovedasa.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Obtener el header si viene del cliente
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);

        // 2. Si no viene, generar un nuevo ID con prefijo "req-"
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "req-" + UUID.randomUUID();
        }

        log.debug("Procesando request en Gateway con Correlation ID: {}", correlationId);

        // 3. Mutar la petición saliente hacia el microservicio para inyectarle el
        // header
        ServerHttpRequest requestMutada = exchange.getRequest()
                .mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        // 4. Agregar el header también en la respuesta hacia el cliente
        exchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, correlationId);

        // 5. Continuar con la cadena reactiva usando el exchange mutado
        return chain.filter(exchange.mutate().request(requestMutada).build());
    }

    @Override
    public int getOrder() {
        // HIGHEST_PRECEDENCE para que sea lo primero que se ejecute al entrar
        return Ordered.HIGHEST_PRECEDENCE;
    }
}