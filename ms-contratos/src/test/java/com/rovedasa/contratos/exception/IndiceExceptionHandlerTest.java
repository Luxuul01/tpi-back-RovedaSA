package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndiceExceptionHandlerTest {

    private final IndiceExceptionHandler handler = new IndiceExceptionHandler();

    @Test
    @DisplayName("Una ruta inexistente responde 404 con ApiError, no 500")
    void rutaInexistenteDa404() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/demo/contratos/simulacion");

        ResponseEntity<ApiError> respuesta = handler.handleRutaInexistente(
                new NoResourceFoundException(HttpMethod.PUT, "api/demo/contratos/simulacion"), request);

        assertEquals(404, respuesta.getStatusCode().value());
        assertEquals(404, respuesta.getBody().getStatus());
    }

    @Test
    @DisplayName("Índice no disponible responde 503")
    void indiceNoDisponibleDa503() {
        ResponseEntity<ApiError> respuesta = handler.handleIndiceNoDisponible(
                new IndiceNoDisponibleException("sin dato"), new MockHttpServletRequest("POST", "/api/contratos/1/ajustes"));

        assertEquals(503, respuesta.getStatusCode().value());
    }
}
