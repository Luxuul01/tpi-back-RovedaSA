package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.contratos.controller.ContratoController;
import com.rovedasa.contratos.controller.CuotaController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;

/**
 * Manejo específico de errores para ContratoController y CuotaController.
 * Traduce fallas de servicios externos (Catálogo / BCRA) a 503 y errores de parámetros a 400.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ContratoController.class, CuotaController.class})
public class ContratoExceptionHandler {

    @ExceptionHandler(ServicioExternoNoDisponibleException.class)
    public ResponseEntity<ApiError> handleServicioNoDisponible(ServicioExternoNoDisponibleException ex,
                                                               HttpServletRequest request) {
        log.warn("Servicio externo no disponible: {}", ex.getMessage());
        return construir(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTipoInvalido(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String mensaje = "Valor inválido para el parámetro '" + ex.getName() + "': " + ex.getValue();
        log.warn("Parámetro inválido en contratos/cuotas: {}", mensaje);
        return construir(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleParametroFaltante(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        String mensaje = "Falta el parámetro obligatorio '" + ex.getParameterName() + "'";
        log.warn("Parámetro faltante en contratos/cuotas: {}", mensaje);
        return construir(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    private ResponseEntity<ApiError> construir(HttpStatus status, String mensaje, HttpServletRequest request) {
        ApiError error = ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(mensaje)
                .path(request.getRequestURI())
                .correlationId(MDC.get("correlationId"))
                .build();
        return ResponseEntity.status(status).body(error);
    }
}
