package com.rovedasa.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rovedasa.gateway.filter.CorrelationIdWebFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respuestas 401 y 403 del Gateway con el mismo formato ApiError que usan los microservicios
 * (timestamp, status, error, message, path, correlationId), como pide el Alcance (sección 10).
 *
 * - 401: sin token, token inválido o vencido.
 * - 403: el rol no tiene permiso (por ejemplo, un POST de un PROPIETARIO o INQUILINO).
 */
@Slf4j
public class ErroresSeguridadHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return escribir(exchange, HttpStatus.UNAUTHORIZED,
                "Se requiere un token válido (falta, es inválido o está vencido)");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return escribir(exchange, HttpStatus.FORBIDDEN,
                "El rol del usuario no tiene permiso para esta operación");
    }

    Mono<Void> escribir(ServerWebExchange exchange, HttpStatus status, String mensaje) {
        Object atributo = exchange.getAttribute(CorrelationIdWebFilter.ATRIBUTO_CORRELATION_ID);
        String correlationId = atributo != null ? atributo.toString() : "N/A";
        String path = exchange.getRequest().getPath().value();
        log.warn("{} en el Gateway: {} {} [{}]", status.value(), exchange.getRequest().getMethod(), path, correlationId);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("timestamp", LocalDateTime.now().toString());
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", mensaje);
        error.put("path", path);
        error.put("correlationId", correlationId);

        byte[] cuerpo;
        try {
            cuerpo = objectMapper.writeValueAsBytes(error);
        } catch (Exception e) {
            cuerpo = ("{\"status\":" + status.value() + "}").getBytes(StandardCharsets.UTF_8);
        }

        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(CorrelationIdWebFilter.CORRELATION_ID_HEADER, correlationId);
        DataBuffer buffer = response.bufferFactory().wrap(cuerpo);
        return response.writeWith(Mono.just(buffer));
    }
}
