package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Solo los 503 propios de ms-contratos. La ruta inexistente (404), los parámetros inválidos o faltantes (400)
 * y el resto de los errores transversales se prueban en el GlobalExceptionHandlerTest de commons.
 */
class IndiceExceptionHandlerTest {

    private final IndiceExceptionHandler handler = new IndiceExceptionHandler();

    @BeforeEach
    void cargarCorrelationId() {
        MDC.put("correlationId", "req-test-503");
    }

    @AfterEach
    void limpiarCorrelationId() {
        MDC.remove("correlationId");
    }

    @Test
    @DisplayName("Índice no disponible responde 503 con ApiError")
    void indiceNoDisponibleDa503() {
        ResponseEntity<ApiError> respuesta = handler.handleIndiceNoDisponible(
                new IndiceNoDisponibleException("sin dato"), new MockHttpServletRequest("POST", "/api/contratos/1/ajustes"));

        assertEquals(503, respuesta.getStatusCode().value());
        assertEquals(503, respuesta.getBody().getStatus());
        assertEquals("sin dato", respuesta.getBody().getMessage());
        assertEquals("req-test-503", respuesta.getBody().getCorrelationId());
    }

    @Test
    @DisplayName("Catálogo (o Keycloak para el token de servicio) no disponible responde 503 con ApiError")
    void catalogoNoDisponibleDa503() {
        ResponseEntity<ApiError> respuesta = handler.handleCatalogoNoDisponible(
                new CatalogoNoDisponibleException("Catálogo no disponible"),
                new MockHttpServletRequest("POST", "/api/contratos/1/activar"));

        assertEquals(503, respuesta.getStatusCode().value());
        assertEquals("/api/contratos/1/activar", respuesta.getBody().getPath());
        assertEquals("req-test-503", respuesta.getBody().getCorrelationId());
    }
}
