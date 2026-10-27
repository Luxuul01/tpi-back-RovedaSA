package com.rovedasa.catalogo.client.georef;

import com.rovedasa.catalogo.exception.ServicioExternoNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeoRefClientTest {

    private static final String URL_BASE = "https://georef.test/api";
    // Así queda la URL encodeada para Independencia 900, Córdoba, Córdoba
    private static final String URL_INDEPENDENCIA_900 = URL_BASE
            + "/direcciones?direccion=Independencia%20900&provincia=C%C3%B3rdoba&localidad_censal=C%C3%B3rdoba&max=1";

    private MockRestServiceServer servidor;
    private GeoRefClient geoRefClient;

    @BeforeEach
    void enlazarServidorSimulado() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL_BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        geoRefClient = new GeoRefClient(builder.build());
    }

    @Test
    void debeMapearLaRespuestaRealDeGeoRef() {
        servidor.expect(requestTo(URL_INDEPENDENCIA_900))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new ClassPathResource("georef/direccion-independencia-900.json"),
                        MediaType.APPLICATION_JSON));

        DireccionNormalizada direccion = geoRefClient
                .normalizar("Independencia", "900", "Córdoba", "Córdoba")
                .orElseThrow();

        assertEquals("INDEPENDENCIA 900, Capital, Córdoba", direccion.nomenclatura());
        assertEquals("Córdoba", direccion.provincia());
        assertEquals("Córdoba", direccion.ciudad());
        assertEquals("Capital", direccion.departamento());
        assertEquals(-31.4266835418118, direccion.latitud());
        assertEquals(-64.1885105207543, direccion.longitud());
        servidor.verify();
    }

    @Test
    void debeEncodearEspaciosAcentosYCaracteresReservados() {
        // Espacios -> %20, acentos -> UTF-8 (%C3%B3), y & / + no deben partir ni alterar el parámetro
        servidor.expect(requestTo(URL_BASE
                        + "/direcciones?direccion=Pje%20Ca%C3%B1ada%20%26%20Col%C3%B3n%2B%20100"
                        + "&provincia=C%C3%B3rdoba&localidad_censal=Villa%20Mar%C3%ADa&max=1"))
                .andRespond(withSuccess(new ClassPathResource("georef/direccion-obispo-trejo-1200.json"),
                        MediaType.APPLICATION_JSON));

        geoRefClient.normalizar(" Pje Cañada & Colón+ ", "100", "Córdoba", "Villa María");

        servidor.verify();
    }

    @Test
    void sinCiudadNoDebeFiltrarPorLocalidad() {
        servidor.expect(requestTo(URL_BASE + "/direcciones?direccion=Independencia%20900&provincia=C%C3%B3rdoba&max=1"))
                .andRespond(withSuccess(new ClassPathResource("georef/direccion-independencia-900.json"),
                        MediaType.APPLICATION_JSON));

        assertTrue(geoRefClient.normalizar("Independencia", "900", "Córdoba", " ").isPresent());
        servidor.verify();
    }

    @Test
    void ceroResultadosDevuelveVacio() {
        // Respuesta real: GeoRef tiene la calle cargada como "OSPO TREJO Y SANABRIA" y no encuentra "Obispo Trejo"
        servidor.expect(requestTo(URL_BASE
                        + "/direcciones?direccion=Obispo%20Trejo%201200&provincia=C%C3%B3rdoba"
                        + "&localidad_censal=C%C3%B3rdoba&max=1"))
                .andRespond(withSuccess(new ClassPathResource("georef/direccion-obispo-trejo-1200.json"),
                        MediaType.APPLICATION_JSON));

        Optional<DireccionNormalizada> direccion = geoRefClient.normalizar("Obispo Trejo", "1200", "Córdoba", "Córdoba");

        assertTrue(direccion.isEmpty());
        servidor.verify();
    }

    @Test
    void resultadoSinUbicacionDevuelveVacio() {
        String respuestaSinUbicacion = """
                {"cantidad":1,"direcciones":[{"altura":{"unidad":null,"valor":900},
                "calle":{"categoria":"CALLE","id":"1401401026225","nombre":"INDEPENDENCIA"},
                "departamento":{"id":"14014","nombre":"Capital"},
                "localidad_censal":{"id":"14014010","nombre":"Córdoba"},
                "nomenclatura":"INDEPENDENCIA 900, Capital, Córdoba","piso":null,
                "provincia":{"id":"14","nombre":"Córdoba"},
                "ubicacion":{"lat":null,"lon":null}}],"inicio":0,"total":1}
                """;
        servidor.expect(requestTo(URL_INDEPENDENCIA_900))
                .andRespond(withSuccess(respuestaSinUbicacion, MediaType.APPLICATION_JSON));

        assertTrue(geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba").isEmpty());
        servidor.verify();
    }

    @Test
    void error500LanzaServicioNoDisponible() {
        servidor.expect(requestTo(URL_INDEPENDENCIA_900)).andRespond(withServerError());

        ServicioExternoNoDisponibleException ex = assertThrows(ServicioExternoNoDisponibleException.class,
                () -> geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba"));

        assertInstanceOf(HttpServerErrorException.class, ex.getCause());
        servidor.verify();
    }

    @Test
    void error400LanzaServicioNoDisponible() {
        // Cuerpo real de GeoRef cuando falta un parámetro obligatorio
        String errorReal = """
                {"errores":[{"codigo_interno":1003,"mensaje":"El parámetro 'direccion' es obligatorio.",
                "nombre_parametro":"direccion","ubicacion":"querystring"}]}
                """;
        servidor.expect(requestTo(URL_INDEPENDENCIA_900))
                .andRespond(withBadRequest().body(errorReal).contentType(MediaType.APPLICATION_JSON));

        ServicioExternoNoDisponibleException ex = assertThrows(ServicioExternoNoDisponibleException.class,
                () -> geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba"));

        assertInstanceOf(HttpClientErrorException.class, ex.getCause());
        servidor.verify();
    }

    @Test
    void timeoutLanzaServicioNoDisponible() {
        servidor.expect(requestTo(URL_INDEPENDENCIA_900))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        ServicioExternoNoDisponibleException ex = assertThrows(ServicioExternoNoDisponibleException.class,
                () -> geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba"));

        assertInstanceOf(ResourceAccessException.class, ex.getCause());
        servidor.verify();
    }
}
