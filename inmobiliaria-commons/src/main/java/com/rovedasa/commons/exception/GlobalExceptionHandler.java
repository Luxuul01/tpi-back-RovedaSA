package com.rovedasa.commons.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manejo de errores transversal de los dos microservicios: todas las respuestas de error salen con el
 * mismo {@link ApiError} (y el correlationId de la petición).
 *
 * Tiene la prioridad más baja (sin @Order): cada servicio puede tener su propio @RestControllerAdvice con
 * HIGHEST_PRECEDENCE para sus excepciones específicas (por ejemplo, 503 de un servicio externo), y lo que no
 * resuelve ahí cae acá. Los mensajes técnicos (SQL, trazas, detalle del token) solo van al log.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String MENSAJE_NO_AUTENTICADO =
            "Se requiere un token de acceso válido para acceder a este recurso";
    static final String MENSAJE_ACCESO_DENEGADO = "No tenés permisos para realizar esta operación";
    static final String MENSAJE_CUERPO_ILEGIBLE = "Cuerpo de la solicitud inválido o mal formado";
    static final String MENSAJE_PARAMETROS_INVALIDOS = "Parámetros inválidos en la solicitud";
    static final String MENSAJE_CONCURRENCIA =
            "El recurso fue modificado por otra operación al mismo tiempo; volvé a consultarlo y reintentá";
    static final String MENSAJE_INTEGRIDAD = "Conflicto con datos ya registrados";
    static final String MENSAJE_DATO_FUERA_DE_RANGO = "Algún dato está fuera del rango permitido";
    // SQLState clase 22 ("data exception"): valor fuera de rango, carácter inválido (NUL), fecha imposible
    private static final String CLASE_SQLSTATE_DATO_INVALIDO = "22";
    static final String MENSAJE_ERROR_INTERNO = "Ocurrió un error inesperado en el servidor. "
            + "Comunicate con soporte e indicá el identificador de error (correlationId).";
    private static final String FORMATO_FECHA = "yyyy-MM-dd";

    // ---------- Excepciones de negocio compartidas ----------

    // 404: el recurso no existe (o es ajeno, para no revelar que existe)
    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ApiError> handleNotFound(RecursoNoEncontradoException ex, HttpServletRequest request) {
        log.warn("Recurso no encontrado: {}", ex.getMessage());
        return responder(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    // 400: regla de negocio violada (el mensaje es de negocio, se puede mostrar)
    @ExceptionHandler(OperacionInvalidaException.class)
    public ResponseEntity<ApiError> handleBadRequest(OperacionInvalidaException ex, HttpServletRequest request) {
        log.warn("Operación inválida: {}", ex.getMessage());
        return responder(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // ---------- Validación y lectura de la petición (400) ----------

    // Bean Validation sobre un @RequestBody @Valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> validaciones = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validaciones.merge(fieldError.getField(), String.valueOf(fieldError.getDefaultMessage()),
                    (anterior, nuevo) -> anterior + "; " + nuevo);
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            validaciones.merge(error.getObjectName(), String.valueOf(error.getDefaultMessage()),
                    (anterior, nuevo) -> anterior + "; " + nuevo);
        }
        log.warn("Error de validación en los campos: {}", validaciones.keySet());
        return responderConValidaciones(MENSAJE_PARAMETROS_INVALIDOS, validaciones, request);
    }

    // Validación de parámetros del método (Spring 6.1), ej. una restricción sobre un @RequestParam o @PathVariable
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleValidacionDeParametros(HandlerMethodValidationException ex,
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
        log.warn("Error de validación de parámetros: {}", validaciones.keySet());
        return responderConValidaciones(MENSAJE_PARAMETROS_INVALIDOS, validaciones, request);
    }

    // JSON mal formado. Si es un enum o una fecha inválida, se indica el campo y lo que se esperaba
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String mensaje = MENSAJE_CUERPO_ILEGIBLE;
        InvalidFormatException formatoInvalido = buscarCausa(ex, InvalidFormatException.class);
        if (formatoInvalido != null && formatoInvalido.getTargetType() != null) {
            String esperado = descripcionDelTipo(formatoInvalido.getTargetType());
            if (esperado != null) {
                mensaje = String.format("Valor inválido '%s' para el campo '%s'. %s",
                        formatoInvalido.getValue(), rutaDelCampo(formatoInvalido), esperado);
            }
        }
        log.warn("Cuerpo ilegible: {}", causaRaiz(ex).getMessage());
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    // Parámetro de query o de path con un tipo inválido (ej. ?estado=VENDIDA, /propiedades/abc, ?fecha=26-10-2026)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTipoDeParametro(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        String mensaje = String.format("Valor inválido '%s' para el parámetro '%s'", ex.getValue(), ex.getName());
        String esperado = ex.getRequiredType() != null ? descripcionDelTipo(ex.getRequiredType()) : null;
        if (esperado != null) {
            mensaje += ". " + esperado;
        }
        log.warn("Parámetro inválido: {}", mensaje);
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleParametroFaltante(MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        String mensaje = "Falta el parámetro obligatorio '" + ex.getParameterName() + "'";
        log.warn(mensaje);
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleHeaderFaltante(MissingRequestHeaderException ex,
            HttpServletRequest request) {
        String mensaje = "Falta el header obligatorio '" + ex.getHeaderName() + "'";
        log.warn(mensaje);
        return responder(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    // ---------- Seguridad ----------

    // 401: sin token, token inválido o vencido. Llega desde la cadena de filtros (el entry point de cada
    // SecurityConfig lo delega acá). El detalle (firma inválida, vencido, etc.) solo va al log y al WWW-Authenticate.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleNoAutenticado(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Petición no autenticada a {}: {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.UNAUTHORIZED, MENSAJE_NO_AUTENTICADO, request);
    }

    // 403: autenticado pero sin el rol que exige la URL o el @PreAuthorize
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Acceso denegado a {}: {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.FORBIDDEN, MENSAJE_ACCESO_DENEGADO, request);
    }

    // ---------- Concurrencia e integridad (409) ----------

    // Versión desactualizada (@Version): otra operación modificó el recurso en el medio
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> handleConcurrencia(RuntimeException ex, HttpServletRequest request) {
        log.warn("Conflicto de concurrencia ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        return responder(HttpStatus.CONFLICT, MENSAJE_CONCURRENCIA, request);
    }

    // Unique o FK violada en la base (409). Si la base rechazó un valor por su contenido (SQLState clase 22)
    // no es un conflicto sino un dato inválido (400). El detalle (SQL, nombre de la constraint) solo va al log
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegridad(DataIntegrityViolationException ex, HttpServletRequest request) {
        SQLException sql = sqlExceptionRaiz(ex);
        if (sql != null && sql.getSQLState() != null
                && sql.getSQLState().startsWith(CLASE_SQLSTATE_DATO_INVALIDO)) {
            log.warn("Dato rechazado por la base (SQLState {}): {}", sql.getSQLState(), sql.getMessage());
            return responder(HttpStatus.BAD_REQUEST, MENSAJE_DATO_FUERA_DE_RANGO, request);
        }
        log.warn("Violación de integridad de datos: {}", causaRaiz(ex).getMessage());
        return responder(HttpStatus.CONFLICT, MENSAJE_INTEGRIDAD, request);
    }

    // ---------- Errores de Spring MVC ----------

    // 404: no hay ningún endpoint para esa ruta
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleRutaInexistente(Exception ex, HttpServletRequest request) {
        log.warn("Ruta inexistente: {} {}", request.getMethod(), request.getRequestURI());
        return responder(HttpStatus.NOT_FOUND, "No existe el recurso " + request.getRequestURI(), request);
    }

    // 405: la ruta existe pero no con ese método. Se informa en el header Allow cuáles sí
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        log.warn("Método HTTP no soportado: {} {}", ex.getMethod(), request.getRequestURI());
        ApiError error = ApiError.de(HttpStatus.METHOD_NOT_ALLOWED,
                "Método HTTP no soportado para este endpoint: " + ex.getMethod(), request.getRequestURI());
        ResponseEntity.BodyBuilder respuesta = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_JSON);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            respuesta.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return respuesta.body(error);
    }

    // 406: el cliente pide (Accept) un formato que el endpoint no produce. El ApiError sale igual en JSON
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleNoAceptable(HttpMediaTypeNotAcceptableException ex,
            HttpServletRequest request) {
        log.warn("Formato de respuesta no aceptable ({}): {}", request.getHeader(HttpHeaders.ACCEPT), ex.getMessage());
        return responder(HttpStatus.NOT_ACCEPTABLE,
                "El formato de respuesta pedido en el header Accept no está disponible; usá application/json",
                request);
    }

    // 415: el body viene con un Content-Type que el endpoint no acepta
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleTipoDeContenidoNoSoportado(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        String recibido = ex.getContentType() != null ? ex.getContentType().toString() : "sin Content-Type";
        log.warn("Content-Type no soportado: {}", recibido);
        return responder(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Tipo de contenido no soportado (" + recibido + "); usá application/json", request);
    }

    // ---------- Todo lo demás ----------

    // Otros errores de Spring MVC que ya traen su status (4xx) lo conservan; el resto es un 500 sanitizado
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            log.warn("Petición rechazada por Spring MVC ({}): {}", status.value(), ex.getMessage());
            return responder(status, "La solicitud no pudo procesarse (" + status.value() + ")", request);
        }
        // El log.error guarda la traza completa en el archivo de errores; al cliente solo un mensaje seguro
        log.error("Error interno no controlado en el servidor", ex);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, MENSAJE_ERROR_INTERNO, request);
    }

    // ---------- Utilidades (también las usan los handlers propios de cada servicio) ----------

    /**
     * Respuesta de error con ApiError en JSON. El Content-Type queda fijo para que el ApiError se escriba
     * aunque el cliente haya pedido otro formato.
     */
    public static ResponseEntity<ApiError> responder(HttpStatusCode status, String mensaje,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.de(status, mensaje, request.getRequestURI()));
    }

    private static ResponseEntity<ApiError> responderConValidaciones(String mensaje, Map<String, String> validaciones,
            HttpServletRequest request) {
        ApiError error = ApiError.de(HttpStatus.BAD_REQUEST, mensaje, request.getRequestURI());
        error.setValidaciones(validaciones);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    /**
     * Qué se esperaba para un tipo que el cliente suele escribir mal (enum o fecha); null para el resto.
     */
    private static String descripcionDelTipo(Class<?> tipo) {
        if (tipo.isEnum()) {
            return "Valores permitidos: " + Arrays.stream(tipo.getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }
        if (LocalDate.class.equals(tipo)) {
            return "Formato esperado: " + FORMATO_FECHA;
        }
        if (LocalDateTime.class.equals(tipo)) {
            return "Formato esperado: " + FORMATO_FECHA + "THH:mm:ss";
        }
        return null;
    }

    private static String rutaDelCampo(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(referencia -> referencia.getFieldName() != null
                        ? referencia.getFieldName()
                        : "[" + referencia.getIndex() + "]")
                .collect(Collectors.joining("."));
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

    // La SQLException más profunda de la cadena (la del driver, que trae el SQLState real)
    private static SQLException sqlExceptionRaiz(Throwable ex) {
        SQLException encontrada = null;
        for (Throwable actual = ex; actual != null; actual = actual.getCause()) {
            if (actual instanceof SQLException sql) {
                encontrada = sql;
            }
            if (actual.getCause() == actual) {
                break;
            }
        }
        return encontrada;
    }

    private static Throwable causaRaiz(Throwable ex) {
        Throwable actual = ex;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual;
    }
}
