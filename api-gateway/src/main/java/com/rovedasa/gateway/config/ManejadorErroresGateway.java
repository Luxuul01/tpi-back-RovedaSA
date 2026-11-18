package com.rovedasa.gateway.config;

import io.netty.channel.unix.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

/**
 * Errores del propio Gateway con formato ApiError y X-Correlation-ID, en lugar del 404 con body vacío o el
 * JSON por defecto de Spring Boot:
 * - 404: no hay ninguna ruta para el path pedido (por ejemplo GET /api/foo).
 * - 503: el microservicio de destino no acepta conexiones (caído o todavía arrancando).
 * - 503: Keycloak no entrega las claves (JWKS) para validar el token. No es un 401: el token puede ser válido.
 * - 502: el microservicio cortó la conexión a mitad del request (se cayó o se reinició mientras respondía).
 * - 504: el microservicio no respondió dentro del response-timeout (20 s).
 * - 500: cualquier otro error inesperado (el detalle solo va al log).
 *
 * Cada línea de log lleva el correlationId en el texto: el MDC no se carga en el Gateway reactivo, así que
 * es la forma de encontrar en Grafana los errores que corta el Gateway (T-01).
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
        String correlationId = EscritorApiError.correlationId(exchange);

        if (esServidorDeIdentidadCaido(ex)) {
            log.warn("No se pudo validar el token para {} {} (correlationId={}): {}",
                    metodo, path, correlationId, ex.toString());
            return EscritorApiError.escribir(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo validar el token: el servidor de identidad no responde");
        }
        if (esServicioInaccesible(ex)) {
            log.warn("Servicio de destino no disponible para {} {} (correlationId={}): {}",
                    metodo, path, correlationId, ex.toString());
            return EscritorApiError.escribir(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de destino no está disponible en este momento; reintentá en unos segundos");
        }
        if (esConexionCortada(ex)) {
            log.warn("El servicio de destino cortó la conexión para {} {} (correlationId={}): {}",
                    metodo, path, correlationId, ex.toString());
            return EscritorApiError.escribir(exchange, HttpStatus.BAD_GATEWAY,
                    "El servicio de destino cortó la conexión antes de responder; si era una modificación, "
                            + "consultá si se aplicó antes de reintentar");
        }
        if (ex instanceof ResponseStatusException respuesta) {
            HttpStatus status = HttpStatus.resolve(respuesta.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            log.warn("{} en el Gateway para {} {} (correlationId={}): {}",
                    status.value(), metodo, path, correlationId, ex.getMessage());
            return EscritorApiError.escribir(exchange, status, mensajePara(status, metodo, path));
        }
        if (buscarCausa(ex, TimeoutException.class) != null) {
            log.warn("Timeout del servicio de destino para {} {} (correlationId={})", metodo, path, correlationId);
            return EscritorApiError.escribir(exchange, HttpStatus.GATEWAY_TIMEOUT,
                    mensajePara(HttpStatus.GATEWAY_TIMEOUT, metodo, path));
        }
        log.error("Error inesperado en el Gateway para {} {} (correlationId={})", metodo, path, correlationId, ex);
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

    // Falló la validación del token por algo que no es el token: la cadena de seguridad relanza la
    // AuthenticationServiceException, y si no se pudo bajar el JWKS llega un IllegalStateException
    // ("Could not obtain the keys") con la WebClientException adentro. En el Gateway el único WebClient es el del
    // JWKS (el ruteo a los microservicios usa el HttpClient de Netty), así que una WebClientException es Keycloak.
    // Va antes de esServicioInaccesible: con Keycloak apagado también hay un ConnectException en la cadena
    private static boolean esServidorDeIdentidadCaido(Throwable ex) {
        return buscarCausa(ex, AuthenticationServiceException.class) != null
                || buscarCausa(ex, WebClientException.class) != null;
    }

    // La conexión con el microservicio se cerró o se reseteó a mitad del request (el servicio se cayó o se
    // reinició mientras respondía). Va después de esServicioInaccesible: ConnectException también es SocketException
    private static boolean esConexionCortada(Throwable ex) {
        return buscarCausa(ex, PrematureCloseException.class) != null
                || buscarCausa(ex, ClosedChannelException.class) != null
                || buscarCausa(ex, SocketException.class) != null
                || buscarCausa(ex, Errors.NativeIoException.class) != null;
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
