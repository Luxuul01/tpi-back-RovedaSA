package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.commons.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores propios de ms-contratos: 503 cuando un servicio del que depende no está disponible (índice del BCRA
 * sin caché válido, o el catálogo / Keycloak caídos). Lo transversal (400, 401, 403, 404, 405, 406, 409, 415,
 * 500) lo resuelve el GlobalExceptionHandler de inmobiliaria-commons; este advice corre antes
 * (HIGHEST_PRECEDENCE) y devuelve el mismo ApiError.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IndiceExceptionHandler {

    @ExceptionHandler(IndiceNoDisponibleException.class)
    public ResponseEntity<ApiError> handleIndiceNoDisponible(IndiceNoDisponibleException ex,
                                                             HttpServletRequest request) {
        log.warn("Índice no disponible: {}", ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(CatalogoNoDisponibleException.class)
    public ResponseEntity<ApiError> handleCatalogoNoDisponible(CatalogoNoDisponibleException ex,
                                                               HttpServletRequest request) {
        log.warn("Servicio externo no disponible: {}", ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }
}
