package com.rovedasa.contratos.client.bcra;

import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Simula el BCRA con MockRestServiceServer (Apunte 18), sin conexión real. */
class BcraEstadisticasClientTest {

    private static final String URL_ICL =
            "https://api.bcra.gob.ar/estadisticas/v4.0/monetarias/40?desde=2026-10-24&hasta=2026-10-26";

    private MockRestServiceServer server;
    private BcraEstadisticasClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bcra.gob.ar");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BcraEstadisticasClient(builder.build());
    }

    @Test
    @DisplayName("Convierte el JSON real del BCRA a DTOs")
    void obtenerSerieOk() {
        String json = """
                {"status":200,"metadata":{"resultset":{"count":3,"offset":0,"limit":1000}},
                 "results":[{"idVariable":40,"detalle":[
                   {"fecha":"2026-10-26","valor":37.09000000000},
                   {"fecha":"2026-10-25","valor":37.07000000000},
                   {"fecha":"2026-10-24","valor":37.05000000000}]}]}
                """;
        server.expect(requestTo(URL_ICL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<BcraValorDTO> valores = client.obtenerSerie(40, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(3, valores.size()),
                () -> assertEquals(LocalDate.of(2026, 10, 26), valores.get(0).getFecha()),
                () -> assertEquals(0, new BigDecimal("37.09").compareTo(valores.get(0).getValor())));
        server.verify();
    }

    @Test
    @DisplayName("Un 500 del BCRA se propaga como RestClientException")
    void errorDelServidor() {
        server.expect(requestTo(URL_ICL)).andRespond(withServerError());
        assertThrows(RestClientException.class,
                () -> client.obtenerSerie(40, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("Una respuesta sin 'results' se considera inesperada")
    void respuestaInesperada() {
        server.expect(requestTo(URL_ICL))
                .andRespond(withSuccess("{\"status\":200}", MediaType.APPLICATION_JSON));
        assertThrows(RestClientException.class,
                () -> client.obtenerSerie(40, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("Último valor hasta una fecha: pide hasta=fecha y limit=1, sin 'desde'")
    void obtenerUltimoValorOk() {
        String json = """
                {"status":200,"metadata":{"resultset":{"count":2315,"offset":0,"limit":1}},
                 "results":[{"idVariable":40,"detalle":[{"fecha":"2026-11-01","valor":37.21000000000}]}]}
                """;
        server.expect(requestTo("https://api.bcra.gob.ar/estadisticas/v4.0/monetarias/40?hasta=2026-11-01&limit=1"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<BcraValorDTO> valores = client.obtenerUltimoValor(40, LocalDate.of(2026, 11, 1));

        assertAll(
                () -> assertEquals(1, valores.size()),
                () -> assertEquals(LocalDate.of(2026, 11, 1), valores.get(0).getFecha()),
                () -> assertEquals(0, new BigDecimal("37.21").compareTo(valores.get(0).getValor())));
        server.verify();
    }

    @Test
    @DisplayName("Sin valores hasta esa fecha (antes del inicio de la serie): lista vacía")
    void obtenerUltimoValorSinDatos() {
        String json = """
                {"status":200,"metadata":{"resultset":{"count":0,"offset":0,"limit":1}},
                 "results":[{"idVariable":40,"detalle":[]}]}
                """;
        server.expect(requestTo("https://api.bcra.gob.ar/estadisticas/v4.0/monetarias/40?hasta=2020-01-05&limit=1"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertEquals(0, client.obtenerUltimoValor(40, LocalDate.of(2020, 1, 5)).size());
    }
}
