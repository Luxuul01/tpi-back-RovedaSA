package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.contratos.controller.IndiceController;
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
 * Manejo de errores propio de los endpoints de índices.
 *
 * Está acotado a IndiceController (assignableTypes) y tiene prioridad sobre el
 * GlobalExceptionHandler de commons, que no contempla 503 ni parámetros mal
 * formados (esos casos hoy terminarían en un 500). Devuelve el mismo ApiError
 * de commons para que todas las respuestas de error tengan el mismo formato.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = IndiceController.class)
public class IndiceExceptionHandler {

    @ExceptionHandler(IndiceNoDisponibleException.class)
    public ResponseEntity<ApiError> handleIndiceNoDisponible(IndiceNoDisponibleException ex,
                                                             HttpServletRequest request) {
        log.warn("Índice no disponible: {}", ex.getMessage());
        return construir(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTipoInvalido(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String mensaje = "Valor inválido para el parámetro '" + ex.getName() + "': " + ex.getValue()
                + ". Las fechas van en formato yyyy-MM-dd y el tipo de índice es ICL o IPC.";
        log.warn("Parámetro inválido: {}", mensaje);
        return construir(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleParametroFaltante(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        String mensaje = "Falta el parámetro obligatorio '" + ex.getParameterName() + "'";
        log.warn("Parámetro faltante: {}", mensaje);
        return construir(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    private ResponseEntity<ApiError> construir(HttpStatus status, String mensaje, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        ApiError error = ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(mensaje)
                .path(request.getRequestURI())
                .correlationId(correlationId != null ? correlationId : "N/A")
                .build();
        return ResponseEntity.status(status).body(error);
    }
}
