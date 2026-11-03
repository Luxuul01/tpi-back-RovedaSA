package com.rovedasa.catalogo.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.rovedasa.commons.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manejo de errores propio del catálogo. Existe porque el GlobalExceptionHandler de commons solo conoce
 * 400 (validación y OperacionInvalida) y 404, y todo lo demás termina en su handler genérico como 500:
 * duplicados, conflictos de concurrencia, GeoRef caído, accesos denegados, JSON mal formado, etc.
 * Corre ANTES que el de commons (HIGHEST_PRECEDENCE) y no maneja Exception genérica, así que lo que no
 * resuelve acá sigue cayendo en commons. Devuelve el mismo ApiError.
 * TODO: debería migrar a inmobiliaria-commons para que ms-contratos tenga las mismas respuestas.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogoExceptionHandler {

    private static final String MENSAJE_CONFLICTO =
            "La operación entra en conflicto con el estado actual del recurso; reintentá";

    // 409: el recurso ya existe (mensaje de negocio, se puede mostrar)
    @ExceptionHandler(RecursoDuplicadoException.class)
    public ResponseEntity<ApiError> manejarDuplicado(RecursoDuplicadoException ex, HttpServletRequest request) {
        log.warn("Recurso duplicado: {}", ex.getMessage());
        return responder(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 409: unique/FK violada en la base o versión desactualizada. El detalle (SQL) solo va al log.
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> manejarConflicto(RuntimeException ex, HttpServletRequest request) {
        log.warn("Conflicto de datos ({}): {}", ex.getClass().getSimpleName(), causaRaiz(ex).getMessage());
        return responder(HttpStatus.CONFLICT, MENSAJE_CONFLICTO, request);
    }

    // 503: GeoRef no responde o devolvió un error
    @ExceptionHandler(ServicioExternoNoDisponibleException.class)
    public ResponseEntity<ApiError> manejarServicioExterno(ServicioExternoNoDisponibleException ex,
            HttpServletRequest request) {
        log.warn("Servicio externo no disponible: {}", ex.getMessage());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    // 403: autenticado pero sin el rol que exige @PreAuthorize
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> manejarAccesoDenegado(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Acceso denegado a {}: {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.FORBIDDEN, "No tenés permisos para realizar esta operación", request);
    }

    // 401: el endpoint necesita el JWT y la petición no lo trae (ej. /propias en perfil local)
    @ExceptionHandler(AutenticacionRequeridaException.class)
    public ResponseEntity<ApiError> manejarAutenticacionRequerida(AutenticacionRequeridaException ex,
            HttpServletRequest request) {
        log.warn("Autenticación requerida en {}: {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    // 400: JSON mal formado. Si es un enum inválido, se indica el campo y los valores permitidos
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> manejarCuerpoIlegible(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        String mensaje = "Cuerpo de la solicitud inválido o mal formado";
        InvalidFormatException formatoInvalido = buscarCausa(ex, InvalidFormatException.class);
        if (formatoInvalido != null && formatoInvalido.getTargetType() != null
                && formatoInvalido.getTargetType().isEnum()) {
            mensaje = String.format("Valor inválido '%s' para el campo '%s'. Valores permitidos: %s",
                    formatoInvalido.getValue(), rutaDelCampo(formatoInvalido),
                    valoresPermitidos(formatoInvalido.getTargetType()));
        }
        log.warn("Cuerpo ilegible en {}: {}", request.getRequestURI(), causaRaiz(ex).getMessage());
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    // 400: parámetro de query o de path con un tipo inválido (ej. ?estado=VENDIDA o /propiedades/abc)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> manejarTipoDeParametro(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        String mensaje = String.format("Valor inválido '%s' para el parámetro '%s'", ex.getValue(), ex.getName());
        Class<?> tipoEsperado = ex.getRequiredType();
        if (tipoEsperado != null && tipoEsperado.isEnum()) {
            mensaje += ". Valores permitidos: " + valoresPermitidos(tipoEsperado);
        }
        log.warn("Parámetro inválido en {}: {}", request.getRequestURI(), mensaje);
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    // 400: validación de parámetros del método (Spring 6.1), ej. una restricción sobre un @RequestParam
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> manejarValidacionDeParametros(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        Map<String, String> validaciones = new LinkedHashMap<>();
        for (ParameterValidationResult resultado : ex.getAllValidationResults()) {
            String parametro = resultado.getMethodParameter().getParameterName();
            if (parametro == null) {
                // Sin -parameters el nombre no está en el bytecode
                parametro = "parametro" + resultado.getMethodParameter().getParameterIndex();
            }
            validaciones.put(parametro, resultado.getResolvableErrors().stream()
                    .map(MessageSourceResolvable::getDefaultMessage)
                    .collect(Collectors.joining("; ")));
        }
        log.warn("Error de validación de parámetros en {}: {}", request.getRequestURI(), validaciones.keySet());
        ResponseEntity<ApiError> respuesta =
                responder(HttpStatus.BAD_REQUEST, "Parámetros inválidos en la solicitud", request);
        respuesta.getBody().setValidaciones(validaciones);
        return respuesta;
    }

    // 400: ordenamiento por una propiedad que no existe (ej. ?sort=precio)
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> manejarPropiedadDeOrdenInvalida(PropertyReferenceException ex,
            HttpServletRequest request) {
        String mensaje = String.format("No se puede ordenar por '%s': no es un campo válido", ex.getPropertyName());
        log.warn("Ordenamiento inválido en {}: {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    // Errores de Spring MVC con status propio (ruta inexistente, método no soportado, etc.), que en commons serían 500
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> manejarErrorDeSpringMvc(Exception ex, HttpServletRequest request) {
        HttpStatusCode status = ((ErrorResponse) ex).getStatusCode();
        log.warn("Petición rechazada por Spring MVC en {}: {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.valueOf(status.value()), ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> responder(HttpStatus status, String mensaje, HttpServletRequest request) {
        ApiError error = ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(mensaje)
                .path(request.getRequestURI())
                .correlationId(obtenerCorrelationId())
                .build();
        return ResponseEntity.status(status).body(error);
    }

    private static String obtenerCorrelationId() {
        String id = MDC.get("correlationId");
        return id != null ? id : "N/A";
    }

    private static String rutaDelCampo(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(referencia -> referencia.getFieldName() != null
                        ? referencia.getFieldName()
                        : "[" + referencia.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String valoresPermitidos(Class<?> tipoEnum) {
        return Arrays.stream(tipoEnum.getEnumConstants())
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    private static <T extends Throwable> T buscarCausa(Throwable ex, Class<T> tipo) {
        for (Throwable actual = ex; actual != null; actual = actual.getCause()) {
            if (tipo.isInstance(actual)) {
                return tipo.cast(actual);
            }
        }
        return null;
    }

    private static Throwable causaRaiz(Throwable ex) {
        Throwable actual = ex;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual;
    }
}
