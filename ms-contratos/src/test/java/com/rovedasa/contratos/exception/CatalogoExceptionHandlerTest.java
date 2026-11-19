package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.client.CatalogoIntegracionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 409 y 502 que ms-contratos devuelve ante respuestas del catálogo (antes estaban en ContratoController).
 * El recorrido HTTP completo lo prueba ContratoControllerTest.
 */
class CatalogoExceptionHandlerTest {

    private final CatalogoExceptionHandler handler = new CatalogoExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/contratos/1/activacion");

    @BeforeEach
    void cargarCorrelationId() {
        MDC.put("correlationId", "req-test-catalogo");
    }

    @AfterEach
    void limpiarCorrelationId() {
        MDC.remove("correlationId");
    }

    @Test
    @DisplayName("Conflicto del catálogo responde 409 con el mensaje y el correlationId")
    void conflictoDa409() {
        ResponseEntity<ApiError> respuesta = handler.handleConflictoCatalogo(
                new CatalogoConflictoException("La propiedad está en estado DISPONIBLE y se esperaba RESERVADA"), request);

        assertEquals(409, respuesta.getStatusCode().value());
        assertEquals(409, respuesta.getBody().getStatus());
        assertEquals("La propiedad está en estado DISPONIBLE y se esperaba RESERVADA", respuesta.getBody().getMessage());
        assertEquals("/api/contratos/1/activacion", respuesta.getBody().getPath());
        assertEquals("req-test-catalogo", respuesta.getBody().getCorrelationId());
    }

    @Test
    @DisplayName("Credencial rechazada o respuesta inesperada del catálogo responde 502")
    void integracionDa502() {
        ResponseEntity<ApiError> respuesta = handler.handleIntegracionCatalogo(
                new CatalogoIntegracionException("El catálogo rechazó la credencial del servicio (HTTP 401)"), request);

        assertEquals(502, respuesta.getStatusCode().value());
        assertEquals("Bad Gateway", respuesta.getBody().getError());
        assertEquals("req-test-catalogo", respuesta.getBody().getCorrelationId());
    }
}
