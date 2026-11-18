package com.rovedasa.contratos.client;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogoClientTest {

    @Test
    void evaluarGarante_devuelve503CuandoCatalogoOBcraNoResponde() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalogo-test/api");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogoClient client = new CatalogoClient(builder.build());

        server.expect(requestTo("http://catalogo-test/api/personas/5/evaluacion-solvencia"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(CatalogoNoDisponibleException.class, () -> client.evaluarGarante(5L));
        server.verify();
    }

    @Test
    void obtenerPropiedad_devuelveRecursoNoEncontradoAnte404() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalogo-test/api");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogoClient client = new CatalogoClient(builder.build());

        server.expect(requestTo("http://catalogo-test/api/propiedades/999"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(RecursoNoEncontradoException.class, () -> client.obtenerPropiedad(999L));
        server.verify();
    }

    private static final String BASE = "http://catalogo-test/api";
    private static final String PROPIEDAD_JSON = "{\"id\":7,\"estado\":\"RESERVADA\"}";

    private MockRestServiceServer server;
    private CatalogoClient client;

    private void armarCliente() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogoClient(builder.build());
    }

    @Test
    void actualizarEstado_mandaElEstadoEsperado() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"nuevoEstado\":\"RESERVADA\",\"estadoEsperado\":\"DISPONIBLE\","
                        + "\"inquilinoId\":3,\"garanteId\":4}", true))
                .andRespond(withSuccess(PROPIEDAD_JSON, MediaType.APPLICATION_JSON));

        client.actualizarEstadoPropiedad(7L, EstadoPropiedadEnum.RESERVADA, EstadoPropiedadEnum.DISPONIBLE, 3L, 4L);
        server.verify();
    }

    @Test
    void liberar_mandaElInquilinoYElGaranteParaQueElCatalogoVerifiqueQueEsElMismoContrato() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"nuevoEstado\":\"DISPONIBLE\",\"estadoEsperado\":\"ALQUILADA\","
                        + "\"inquilinoId\":3,\"garanteId\":4}", true))
                .andRespond(withSuccess(PROPIEDAD_JSON, MediaType.APPLICATION_JSON));

        client.actualizarEstadoPropiedad(7L, EstadoPropiedadEnum.DISPONIBLE, EstadoPropiedadEnum.ALQUILADA, 3L, 4L);
        server.verify();
    }

    @Test
    void compensar_alquiladaAReservadaTambienMandaLasPartes() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado/compensacion"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"estadoActual\":\"ALQUILADA\",\"estadoAnterior\":\"RESERVADA\","
                        + "\"inquilinoId\":3,\"garanteId\":4}", true))
                .andRespond(withSuccess(PROPIEDAD_JSON, MediaType.APPLICATION_JSON));

        client.compensarEstadoPropiedad(7L, EstadoPropiedadEnum.ALQUILADA, EstadoPropiedadEnum.RESERVADA, 3L, 4L);
        server.verify();
    }

    @Test
    void compensar_usaElEndpointDeCompensacionConLasPartes() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado/compensacion"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"estadoActual\":\"DISPONIBLE\",\"estadoAnterior\":\"ALQUILADA\","
                        + "\"inquilinoId\":3,\"garanteId\":4}", true))
                .andRespond(withSuccess(PROPIEDAD_JSON, MediaType.APPLICATION_JSON));

        client.compensarEstadoPropiedad(7L, EstadoPropiedadEnum.DISPONIBLE, EstadoPropiedadEnum.ALQUILADA, 3L, 4L);
        server.verify();
    }

    @Test
    void compensar_sinPartesNoLasManda() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado/compensacion"))
                .andExpect(content().json("{\"estadoActual\":\"ALQUILADA\",\"estadoAnterior\":\"RESERVADA\"}", true))
                .andRespond(withSuccess(PROPIEDAD_JSON, MediaType.APPLICATION_JSON));

        client.compensarEstadoPropiedad(7L, EstadoPropiedadEnum.ALQUILADA, EstadoPropiedadEnum.RESERVADA, null, null);
        server.verify();
    }

    @Test
    void un409DelCatalogoSaleComoConflictoConSuMensaje() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":409,\"message\":\"La propiedad está en estado ALQUILADA y se esperaba RESERVADA\"}"));

        CatalogoConflictoException ex = assertThrows(CatalogoConflictoException.class, () -> client
                .actualizarEstadoPropiedad(7L, EstadoPropiedadEnum.DISPONIBLE, EstadoPropiedadEnum.RESERVADA, null, null));
        assertTrue(ex.getMessage().contains("se esperaba RESERVADA"), ex.getMessage());
    }

    @Test
    void un401o403DelCatalogoEsUnErrorDeIntegracionNoUnaCaida() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE + "/personas/5/evaluacion-solvencia")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        CatalogoIntegracionException ex401 = assertThrows(CatalogoIntegracionException.class,
                () -> client.obtenerPropiedad(7L));
        CatalogoIntegracionException ex403 = assertThrows(CatalogoIntegracionException.class,
                () -> client.evaluarGarante(5L));
        assertTrue(ex401.getMessage().contains("rechazó la credencial del servicio"), ex401.getMessage());
        assertTrue(ex403.getMessage().contains("HTTP 403"), ex403.getMessage());
    }

    @Test
    void un400DelCatalogoReenviaSuMensaje() {
        armarCliente();
        server.expect(requestTo(BASE + "/propiedades/7/estado"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":400,\"message\":\"Transición inválida de DISPONIBLE a ALQUILADA\"}"));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class, () -> client
                .actualizarEstadoPropiedad(7L, EstadoPropiedadEnum.ALQUILADA, EstadoPropiedadEnum.RESERVADA, 3L, 4L));
        assertTrue(ex.getMessage().contains("Transición inválida"), ex.getMessage());
    }
}
