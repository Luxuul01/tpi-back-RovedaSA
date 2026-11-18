package com.rovedasa.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Errores del propio Gateway con formato ApiError y X-Correlation-ID, en lugar del 404 con body vacío o el
 * JSON por defecto de Spring Boot:
 * - 404: no hay ninguna ruta para el path pedido (por ejemplo GET /api/foo).
 * - 503: el microservicio de destino no acepta conexiones (caído o todavía arrancando).
 * - 504: el microservicio no respondió dentro del response-timeout (20 s).
 * - 500: cualquier otro error inesperado (el detalle solo va al log).
 *
 * Tiene orden -2 para correr antes que el DefaultErrorWebExceptionHandler de Spring Boot (orden -1).
 * Los 401 y 403 no pasan por acá: los resuelve la cadena de seguridad con ErroresSeguridadHandler.
 */
@Slf4j
@Component
@Order(-2)
public class ManejadorErroresGateway implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        String metodo = String.valueOf(exchange.getRequest().getMethod());
        String path = exchange.getRequest().getPath().value();

        if (esServicioInaccesible(ex)) {
            log.warn("Servicio de destino no disponible para {} {}: {}", metodo, path, ex.toString());
            return EscritorApiError.escribir(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de destino no está disponible en este momento; reintentá en unos segundos");
        }
        if (ex instanceof ResponseStatusException respuesta) {
            HttpStatus status = HttpStatus.resolve(respuesta.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            log.warn("{} en el Gateway para {} {}: {}", status.value(), metodo, path, ex.getMessage());
            return EscritorApiError.escribir(exchange, status, mensajePara(status, metodo, path));
        }
        if (buscarCausa(ex, TimeoutException.class) != null) {
            log.warn("Timeout del servicio de destino para {} {}", metodo, path);
            return EscritorApiError.escribir(exchange, HttpStatus.GATEWAY_TIMEOUT,
                    mensajePara(HttpStatus.GATEWAY_TIMEOUT, metodo, path));
        }
        log.error("Error inesperado en el Gateway para {} {}", metodo, path, ex);
        return EscritorApiError.escribir(exchange, HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error inesperado en el Gateway. Comunicate con soporte e indicá el identificador "
                        + "de error (correlationId).");
    }

    private static String mensajePara(HttpStatus status, String metodo, String path) {
        return switch (status) {
            case NOT_FOUND -> "No existe ningún recurso para " + metodo + " " + path;
            case GATEWAY_TIMEOUT -> "El servicio de destino no respondió a tiempo";
            case SERVICE_UNAVAILABLE -> "El servicio de destino no está disponible en este momento";
            default -> status.is4xxClientError()
                    ? "La solicitud no pudo procesarse (" + status.value() + ")"
                    : "Ocurrió un error inesperado en el Gateway (" + status.value() + ")";
        };
    }

    // Conexión rechazada, host desconocido o sin ruta: el microservicio no está levantado o no es alcanzable
    private static boolean esServicioInaccesible(Throwable ex) {
        return buscarCausa(ex, ConnectException.class) != null
                || buscarCausa(ex, UnknownHostException.class) != null
                || buscarCausa(ex, NoRouteToHostException.class) != null;
    }

    private static <T extends Throwable> T buscarCausa(Throwable ex, Class<T> tipo) {
        for (Throwable actual = ex; actual != null; actual = actual.getCause()) {
            if (tipo.isInstance(actual)) {
                return tipo.cast(actual);
            }
            if (actual.getCause() == actual) {
                break;
            }
        }
        return null;
    }
}
