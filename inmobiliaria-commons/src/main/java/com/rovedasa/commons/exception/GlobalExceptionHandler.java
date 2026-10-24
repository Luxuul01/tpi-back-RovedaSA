package com.rovedasa.commons.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. Recurso No Encontrado (404)
    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ApiError> handleNotFound(RecursoNoEncontradoException ex,
            HttpServletRequest request) {

        log.warn("[{}] Recurso no encontrado: {}", obtenerCorrelationId(), ex.getMessage());
        ApiError error = buildApiError(HttpStatus.NOT_FOUND, ex.getMessage(), request);

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // 2. Operación Inválida / Regla de Negocio (400)
    @ExceptionHandler(OperacionInvalidaException.class)
    public ResponseEntity<ApiError> handleBadRequest(OperacionInvalidaException ex,
            HttpServletRequest request) {

        log.warn("[{}] Operación inválida: {}", obtenerCorrelationId(), ex.getMessage());
        ApiError error = buildApiError(HttpStatus.BAD_REQUEST, ex.getMessage(), request);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // 3. Fallas de Validación Bean Validation (@Valid) (400)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> validaciones = new HashMap<>();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validaciones.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        log.warn("[{}] Error de validación en los campos: {}", obtenerCorrelationId(), validaciones.keySet());
        ApiError error = buildApiError(HttpStatus.BAD_REQUEST, "Parámetros inválidos en la solicitud", request);
        error.setValidaciones(validaciones);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // 4. Error 500 Inesperado (Sanitizado hacia el cliente, detallado en disco)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {

        String correlationId = obtenerCorrelationId();

        // ¡El log.error guarda la traza y ex completo en el archivo error.log!
        log.error("[{}] Error interno no controlado en el servidor", correlationId, ex);

        // Al cliente solo le devolvemos un mensaje seguro:
        String mensajeSeguro = "Ha ocurrido un error inesperado en el servidor. Por favor, comuníquese con soporte e indique el identificador de error.";
        ApiError error = buildApiError(HttpStatus.INTERNAL_SERVER_ERROR, mensajeSeguro, request);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ApiError buildApiError(HttpStatus status, String message, HttpServletRequest request) {
        return ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .correlationId(obtenerCorrelationId())
                .build();
    }

    private String obtenerCorrelationId() {
        String id = MDC.get("correlationId");
        return (id != null) ? id : "N/A";
    }
}
