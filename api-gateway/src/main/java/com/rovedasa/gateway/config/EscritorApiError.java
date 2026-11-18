package com.rovedasa.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rovedasa.gateway.filter.CorrelationIdWebFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escribe una respuesta de error del Gateway con el mismo formato ApiError que usan los microservicios
 * (timestamp, status, error, message, path, correlationId). El Gateway no usa inmobiliaria-commons
 * (es reactivo y la librería es servlet), por eso arma el mismo JSON con un Map.
 */
final class EscritorApiError {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EscritorApiError() {
    }

    static String correlationId(ServerWebExchange exchange) {
        Object atributo = exchange.getAttribute(CorrelationIdWebFilter.ATRIBUTO_CORRELATION_ID);
        return atributo != null ? atributo.toString() : "N/A";
    }

    static Mono<Void> escribir(ServerWebExchange exchange, HttpStatus status, String mensaje) {
        String correlationId = correlationId(exchange);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("timestamp", LocalDateTime.now().toString());
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", mensaje);
        error.put("path", exchange.getRequest().getPath().value());
        error.put("correlationId", correlationId);

        byte[] cuerpo;
        try {
            cuerpo = MAPPER.writeValueAsBytes(error);
        } catch (Exception e) {
            cuerpo = ("{\"status\":" + status.value() + "}").getBytes(StandardCharsets.UTF_8);
        }

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(cuerpo.length);
        response.getHeaders().set(CorrelationIdWebFilter.CORRELATION_ID_HEADER, correlationId);
        DataBuffer buffer = response.bufferFactory().wrap(cuerpo);
        return response.writeWith(Mono.just(buffer));
    }
}
