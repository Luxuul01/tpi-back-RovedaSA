package com.rovedasa.catalogo.exception;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.commons.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores propios del catálogo. Lo transversal (400 de validación y JSON, 401, 403, 404, 405, 406, 409 de
 * concurrencia e integridad, 415 y 500) lo resuelve el GlobalExceptionHandler de inmobiliaria-commons, igual
 * que en ms-contratos. Este advice corre antes (HIGHEST_PRECEDENCE) y no maneja Exception genérica, así que lo
 * que no resuelve acá sigue cayendo en commons. Devuelve el mismo ApiError.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogoExceptionHandler {

    // 409: el recurso ya existe (mensaje de negocio, se puede mostrar)
    @ExceptionHandler(RecursoDuplicadoException.class)
    public ResponseEntity<ApiError> manejarDuplicado(RecursoDuplicadoException ex, HttpServletRequest request) {
        log.warn("Recurso duplicado: {}", ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 409: el resto de los conflictos de negocio, por ejemplo una propiedad que no está en el estado esperado
    // por la Saga de ms-contratos (mensaje de negocio, se puede mostrar)
    @ExceptionHandler(ConflictoException.class)
    public ResponseEntity<ApiError> manejarConflicto(ConflictoException ex, HttpServletRequest request) {
        log.warn("Conflicto con el estado actual ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 503: GeoRef o la Central de Deudores del BCRA no responden o devolvieron un error.
    // El mensaje de ambas excepciones es de negocio (no expone la causa técnica), así que se puede mostrar.
    @ExceptionHandler({ServicioExternoNoDisponibleException.class, BcraNoDisponibleException.class})
    public ResponseEntity<ApiError> manejarServicioExterno(RuntimeException ex, HttpServletRequest request) {
        log.warn("Servicio externo no disponible: {}", ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    // 400: ordenamiento por una propiedad que no existe (ej. ?sort=precio). Solo el catálogo pagina con Spring Data
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> manejarPropiedadDeOrdenInvalida(PropertyReferenceException ex,
            HttpServletRequest request) {
        String mensaje = String.format("No se puede ordenar por '%s': no es un campo válido", ex.getPropertyName());
        log.warn("Ordenamiento inválido en {}: {}", request.getRequestURI(), ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }
}
