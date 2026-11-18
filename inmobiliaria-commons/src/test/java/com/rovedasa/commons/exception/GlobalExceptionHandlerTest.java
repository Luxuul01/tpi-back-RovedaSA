package com.rovedasa.commons.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapeos del GlobalExceptionHandler llamando a cada método directamente. Los que dependen de cómo Spring MVC
 * arma la excepción (405, 406, 415, header faltante, validación de parámetros) se prueban de punta a punta
 * en GlobalExceptionHandlerMvcTest.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/contratos/1/seniar");

    @BeforeEach
    void cargarCorrelationId() {
        MDC.put("correlationId", "req-test-123");
    }

    @AfterEach
    void limpiarCorrelationId() {
        MDC.remove("correlationId");
    }

    @Test
    @DisplayName("Bloqueo optimista de Spring (ObjectOptimisticLockingFailureException) -> 409")
    void bloqueoOptimistaDeSpringDevuelve409() {
        ResponseEntity<ApiError> respuesta = handler.handleConcurrencia(
                new ObjectOptimisticLockingFailureException("Contrato", 10L), request);

        verificar(respuesta, HttpStatus.CONFLICT);
        assertEquals(GlobalExceptionHandler.MENSAJE_CONCURRENCIA, respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Bloqueo optimista de JPA (OptimisticLockException) -> 409")
    void bloqueoOptimistaDeJpaDevuelve409() {
        ResponseEntity<ApiError> respuesta = handler.handleConcurrencia(
                new OptimisticLockException("Row was updated or deleted by another transaction"), request);

        verificar(respuesta, HttpStatus.CONFLICT);
        assertFalse(respuesta.getBody().getMessage().contains("Row was updated"));
    }

    @Test
    @DisplayName("Violación de integridad -> 409 sin exponer el SQL")
    void violacionDeIntegridadDevuelve409SinExponerElSql() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: duplicate key value violates unique constraint \"uk_cuotas_contrato_nro\""));

        ResponseEntity<ApiError> respuesta = handler.handleIntegridad(ex, request);

        verificar(respuesta, HttpStatus.CONFLICT);
        assertEquals(GlobalExceptionHandler.MENSAJE_INTEGRIDAD, respuesta.getBody().getMessage());
        assertFalse(respuesta.getBody().getMessage().contains("duplicate key"));
    }

    @Test
    @DisplayName("Content-Type no soportado -> 415")
    void contentTypeNoSoportadoDevuelve415() {
        ResponseEntity<ApiError> respuesta = handler.handleTipoDeContenidoNoSoportado(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                request);

        verificar(respuesta, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertTrue(respuesta.getBody().getMessage().contains("text/plain"));
    }

    @Test
    @DisplayName("Accept no satisfacible -> 406, con el ApiError en JSON igual")
    void acceptNoSatisfacibleDevuelve406EnJson() {
        ResponseEntity<ApiError> respuesta = handler.handleNoAceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), request);

        verificar(respuesta, HttpStatus.NOT_ACCEPTABLE);
        assertEquals(MediaType.APPLICATION_JSON, respuesta.getHeaders().getContentType());
    }

    @Test
    @DisplayName("Sin token o token inválido -> 401")
    void noAutenticadoDevuelve401SinElDetalleDelToken() {
        ResponseEntity<ApiError> respuesta = handler.handleNoAutenticado(
                new BadCredentialsException("Jwt expired at 2026-11-17T10:00:00Z"), request);

        verificar(respuesta, HttpStatus.UNAUTHORIZED);
        assertEquals(GlobalExceptionHandler.MENSAJE_NO_AUTENTICADO, respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Rol sin permiso -> 403")
    void accesoDenegadoDevuelve403() {
        ResponseEntity<ApiError> respuesta = handler.handleAccessDenied(new AccessDeniedException("Access Denied"),
                request);

        verificar(respuesta, HttpStatus.FORBIDDEN);
        assertEquals(GlobalExceptionHandler.MENSAJE_ACCESO_DENEGADO, respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Método no soportado -> 405 con header Allow")
    void metodoNoSoportadoDevuelve405ConAllow() {
        ResponseEntity<ApiError> respuesta = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST")), request);

        verificar(respuesta, HttpStatus.METHOD_NOT_ALLOWED);
        assertEquals(2, respuesta.getHeaders().getAllow().size());
    }

    @Test
    @DisplayName("Enum inválido en el body -> 400 con el campo y los valores permitidos")
    void enumInvalidoEnElCuerpoDevuelve400ConCampoYValoresPermitidos() {
        InvalidFormatException errorJackson = assertThrows(InvalidFormatException.class,
                () -> JsonMapper.builder().build().readValue("{\"estado\":\"VENDIDA\"}", ConEstado.class));
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", errorJackson,
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiError> respuesta = handler.handleNotReadable(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Valor inválido 'VENDIDA' para el campo 'estado'. Valores permitidos: "
                + "DISPONIBLE, RESERVADA, ALQUILADA, INACTIVA", respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("JSON mal formado -> 400 con mensaje genérico")
    void jsonMalFormadoDevuelve400ConMensajeGenerico() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiError> respuesta = handler.handleNotReadable(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals(GlobalExceptionHandler.MENSAJE_CUERPO_ILEGIBLE, respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Parámetro enum inválido -> 400 con los valores permitidos")
    void parametroEnumInvalidoDevuelve400ConValoresPermitidos() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException("VENDIDA",
                EstadoPropiedadEnum.class, "estado", null, new IllegalArgumentException("No enum constant"));

        ResponseEntity<ApiError> respuesta = handler.handleTipoDeParametro(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Valor inválido 'VENDIDA' para el parámetro 'estado'. Valores permitidos: "
                + "DISPONIBLE, RESERVADA, ALQUILADA, INACTIVA", respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Parámetro fecha inválido -> 400 indicando el formato")
    void parametroFechaInvalidoDevuelve400ConElFormato() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException("26-10-2026",
                LocalDate.class, "fecha", null, new IllegalArgumentException("Parse attempt failed"));

        ResponseEntity<ApiError> respuesta = handler.handleTipoDeParametro(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Valor inválido '26-10-2026' para el parámetro 'fecha'. Formato esperado: yyyy-MM-dd",
                respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Parámetro obligatorio faltante -> 400")
    void parametroFaltanteDevuelve400() {
        ResponseEntity<ApiError> respuesta = handler.handleParametroFaltante(
                new MissingServletRequestParameterException("fecha", "LocalDate"), request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Falta el parámetro obligatorio 'fecha'", respuesta.getBody().getMessage());
    }

    @Test
    @DisplayName("Otro error de Spring con status 4xx lo conserva en lugar de convertirse en 500")
    void errorDeSpringConStatus4xxLoConserva() {
        ResponseEntity<ApiError> respuesta = handler.handleGeneric(
                new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE), request);

        verificar(respuesta, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("Error inesperado -> 500 con mensaje seguro")
    void errorInesperadoDevuelve500Sanitizado() {
        ResponseEntity<ApiError> respuesta = handler.handleGeneric(
                new IllegalStateException("NullPointer en ContratoService.linea 123"), request);

        verificar(respuesta, HttpStatus.INTERNAL_SERVER_ERROR);
        assertFalse(respuesta.getBody().getMessage().contains("ContratoService"));
    }

    @Test
    @DisplayName("Fuera de una petición (sin MDC) el correlationId es N/A")
    void sinCorrelationIdEnElMdcUsaNA() {
        MDC.remove("correlationId");

        ResponseEntity<ApiError> respuesta = handler.handleNotFound(new RecursoNoEncontradoException("no está"),
                request);

        assertEquals("N/A", respuesta.getBody().getCorrelationId());
    }

    private void verificar(ResponseEntity<ApiError> respuesta, HttpStatus esperado) {
        assertEquals(esperado, respuesta.getStatusCode());
        ApiError cuerpo = respuesta.getBody();
        assertNotNull(cuerpo);
        assertEquals(esperado.value(), cuerpo.getStatus());
        assertEquals(esperado.getReasonPhrase(), cuerpo.getError());
        assertEquals("/api/contratos/1/seniar", cuerpo.getPath());
        assertEquals("req-test-123", cuerpo.getCorrelationId());
        assertNotNull(cuerpo.getTimestamp());
    }

    /** DTO mínimo para provocar el error real de Jackson con un enum inválido. */
    static class ConEstado {
        public EstadoPropiedadEnum estado;
    }
}
