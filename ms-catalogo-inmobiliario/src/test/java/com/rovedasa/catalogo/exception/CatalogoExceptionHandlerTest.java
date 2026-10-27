package com.rovedasa.catalogo.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rovedasa.catalogo.dto.CrearPropiedadRequest;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogoExceptionHandlerTest {

    private final CatalogoExceptionHandler handler = new CatalogoExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/propiedades");

    @BeforeEach
    void cargarCorrelationId() {
        MDC.put("correlationId", "req-test-123");
    }

    @AfterEach
    void limpiarCorrelationId() {
        MDC.remove("correlationId");
    }

    @Test
    void duplicadoDevuelve409ConElMensajeDeNegocio() {
        ResponseEntity<ApiError> respuesta = handler.manejarDuplicado(
                new RecursoDuplicadoException("Ya existe una persona con CUIT/CUIL 20123456786"), request);

        verificar(respuesta, HttpStatus.CONFLICT);
        assertEquals("Ya existe una persona con CUIT/CUIL 20123456786", respuesta.getBody().getMessage());
    }

    @Test
    void violacionDeIntegridadDevuelve409SinExponerElSql() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: duplicate key value violates unique constraint \"uk_personas_email\""));

        ResponseEntity<ApiError> respuesta = handler.manejarConflicto(ex, request);

        verificar(respuesta, HttpStatus.CONFLICT);
        assertFalse(respuesta.getBody().getMessage().contains("duplicate key"));
        assertTrue(respuesta.getBody().getMessage().contains("reintentá"));
    }

    @Test
    void bloqueoOptimistaDevuelve409() {
        ResponseEntity<ApiError> respuesta = handler.manejarConflicto(
                new ObjectOptimisticLockingFailureException("Propiedad", 10L), request);

        verificar(respuesta, HttpStatus.CONFLICT);
    }

    @Test
    void servicioExternoCaidoDevuelve503() {
        ResponseEntity<ApiError> respuesta = handler.manejarServicioExterno(
                new ServicioExternoNoDisponibleException("GeoRef no está disponible", null), request);

        verificar(respuesta, HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals("GeoRef no está disponible", respuesta.getBody().getMessage());
    }

    @Test
    void accesoDenegadoDevuelve403() {
        ResponseEntity<ApiError> respuesta = handler.manejarAccesoDenegado(
                new AccessDeniedException("Access Denied"), request);

        verificar(respuesta, HttpStatus.FORBIDDEN);
    }

    @Test
    void autenticacionRequeridaDevuelve401() {
        ResponseEntity<ApiError> respuesta = handler.manejarAutenticacionRequerida(
                new AutenticacionRequeridaException("Este endpoint requiere un token JWT"), request);

        verificar(respuesta, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void enumInvalidoEnElCuerpoDevuelve400ConCampoYValoresPermitidos() {
        // Se provoca el error real de Jackson al leer una tipología inexistente
        InvalidFormatException errorJackson = assertThrows(InvalidFormatException.class,
                () -> JsonMapper.builder().build().readValue("{\"tipologia\":\"PISCINA\"}", CrearPropiedadRequest.class));
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", errorJackson,
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiError> respuesta = handler.manejarCuerpoIlegible(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Valor inválido 'PISCINA' para el campo 'tipologia'. Valores permitidos: "
                + "DEPARTAMENTO, CASA, PH, LOCAL_COMERCIAL, OFICINA, COCHERA", respuesta.getBody().getMessage());
    }

    @Test
    void jsonMalFormadoDevuelve400ConMensajeGenerico() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error: Unexpected character",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiError> respuesta = handler.manejarCuerpoIlegible(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Cuerpo de la solicitud inválido o mal formado", respuesta.getBody().getMessage());
    }

    @Test
    void parametroConTipoInvalidoDevuelve400IndicandoElParametro() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException("VENDIDA",
                EstadoPropiedadEnum.class, "estado", null, new IllegalArgumentException("No enum constant"));

        ResponseEntity<ApiError> respuesta = handler.manejarTipoDeParametro(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("Valor inválido 'VENDIDA' para el parámetro 'estado'. Valores permitidos: "
                + "DISPONIBLE, RESERVADA, ALQUILADA, INACTIVA", respuesta.getBody().getMessage());
    }

    private void verificar(ResponseEntity<ApiError> respuesta, HttpStatus esperado) {
        assertEquals(esperado, respuesta.getStatusCode());
        ApiError cuerpo = respuesta.getBody();
        assertNotNull(cuerpo);
        assertEquals(esperado.value(), cuerpo.getStatus());
        assertEquals(esperado.getReasonPhrase(), cuerpo.getError());
        assertEquals("/api/propiedades", cuerpo.getPath());
        assertEquals("req-test-123", cuerpo.getCorrelationId());
        assertNotNull(cuerpo.getTimestamp());
    }
}
