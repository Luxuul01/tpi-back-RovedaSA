package com.rovedasa.catalogo.exception;

import com.rovedasa.catalogo.controller.EvaluacionGaranteController;
import com.rovedasa.commons.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;

/**
 * Errores propios de la evaluación de garantes. Acotado a EvaluacionGaranteController y con
 * prioridad sobre el GlobalExceptionHandler de commons (que no contempla el 503).
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = EvaluacionGaranteController.class)
public class BcraExceptionHandler {

    @ExceptionHandler(BcraNoDisponibleException.class)
    public ResponseEntity<ApiError> handleBcraNoDisponible(BcraNoDisponibleException ex, HttpServletRequest request) {
        return construir(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTipoInvalido(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String mensaje = "Valor inválido para el parámetro '" + ex.getName() + "': se espera un número";
        log.warn("Parámetro inválido: {}", mensaje);
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
