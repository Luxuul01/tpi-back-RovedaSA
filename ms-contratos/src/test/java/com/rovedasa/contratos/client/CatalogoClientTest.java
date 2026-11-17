package com.rovedasa.contratos.client;

import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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
}
