package com.rovedasa.catalogo.exception;

import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.slf4j.MDC;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Solo los errores propios del catálogo: los transversales (403, JSON mal formado, 405, 409 de concurrencia,
 * etc.) se prueban en el GlobalExceptionHandlerTest de inmobiliaria-commons.
 */
@ExtendWith(OutputCaptureExtension.class)
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
    void estadoDePropiedadInesperadoDevuelve409ConElMensajeYSinLogDeDuplicado(CapturedOutput salida) {
        ResponseEntity<ApiError> respuesta = handler.manejarConflicto(new EstadoPropiedadInesperadoException(
                EstadoPropiedadEnum.RESERVADA, EstadoPropiedadEnum.DISPONIBLE), request);

        verificar(respuesta, HttpStatus.CONFLICT);
        assertEquals("La propiedad está en estado RESERVADA y se esperaba DISPONIBLE", respuesta.getBody().getMessage());
        assertTrue(salida.getAll().contains("Conflicto con el estado actual (EstadoPropiedadInesperadoException)"));
        assertFalse(salida.getAll().contains("Recurso duplicado"));
    }

    @Test
    void servicioExternoCaidoDevuelve503() {
        ResponseEntity<ApiError> respuesta = handler.manejarServicioExterno(
                new ServicioExternoNoDisponibleException("GeoRef no está disponible", null), request);

        verificar(respuesta, HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals("GeoRef no está disponible", respuesta.getBody().getMessage());
    }

    @Test
    void bcraCaidoDevuelve503ConElMismoFormatoQueGeoRef() {
        ResponseEntity<ApiError> respuesta = handler.manejarServicioExterno(
                new BcraNoDisponibleException("La Central de Deudores del BCRA no está disponible", null), request);

        verificar(respuesta, HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals("La Central de Deudores del BCRA no está disponible", respuesta.getBody().getMessage());
    }

    @Test
    void ordenarPorUnCampoInexistenteDevuelve400() {
        PropertyReferenceException ex = new PropertyReferenceException("precio",
                TypeInformation.of(Propiedad.class), List.of());

        ResponseEntity<ApiError> respuesta = handler.manejarPropiedadDeOrdenInvalida(ex, request);

        verificar(respuesta, HttpStatus.BAD_REQUEST);
        assertEquals("No se puede ordenar por 'precio': no es un campo válido", respuesta.getBody().getMessage());
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
