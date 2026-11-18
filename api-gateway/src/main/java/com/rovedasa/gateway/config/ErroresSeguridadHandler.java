package com.rovedasa.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Respuestas 401 y 403 del Gateway con el mismo formato ApiError que usan los microservicios
 * (timestamp, status, error, message, path, correlationId), como pide el Alcance (sección 10).
 *
 * - 401: sin token, token inválido o vencido. Lleva además el header {@code WWW-Authenticate: Bearer}
 *   (RFC 6750), con {@code error="invalid_token"} cuando el token vino pero no es válido.
 * - 403: el rol no tiene permiso (por ejemplo, un POST de un PROPIETARIO o INQUILINO).
 */
@Slf4j
public class ErroresSeguridadHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, encabezadoWwwAuthenticate(ex));
        return escribir(exchange, HttpStatus.UNAUTHORIZED,
                "Se requiere un token válido (falta, es inválido o está vencido)");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return escribir(exchange, HttpStatus.FORBIDDEN,
                "El rol del usuario no tiene permiso para esta operación");
    }

    Mono<Void> escribir(ServerWebExchange exchange, HttpStatus status, String mensaje) {
        // El correlationId va en el texto: en el Gateway reactivo el MDC está vacío (T-01)
        log.warn("{} en el Gateway: {} {} (correlationId={})", status.value(), exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(), EscritorApiError.correlationId(exchange));
        return EscritorApiError.escribir(exchange, status, mensaje);
    }

    // Sin token: "Bearer". Token presente pero rechazado: el código de error de OAuth2 (sin el detalle,
    // que solo va al log)
    private static String encabezadoWwwAuthenticate(AuthenticationException ex) {
        if (ex instanceof OAuth2AuthenticationException oauth2 && oauth2.getError() != null
                && oauth2.getError().getErrorCode() != null) {
            log.debug("Token rechazado: {}", oauth2.getError().getDescription());
            return "Bearer error=\"" + oauth2.getError().getErrorCode() + "\"";
        }
        return "Bearer";
    }
}
