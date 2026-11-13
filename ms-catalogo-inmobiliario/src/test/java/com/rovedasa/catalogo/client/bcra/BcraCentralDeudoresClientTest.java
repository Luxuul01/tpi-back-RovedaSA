package com.rovedasa.catalogo.client.bcra;

import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResultadoDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudorDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Simula la Central de Deudores con MockRestServiceServer (Apunte 18). */
class BcraCentralDeudoresClientTest {

    private static final String BASE = "https://api.bcra.gob.ar/CentralDeDeudores/v1.0/Deudas/";
    private static final String CUIT = "20123456789";

    private MockRestServiceServer server;
    private BcraCentralDeudoresClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bcra.gob.ar");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BcraCentralDeudoresClient(builder.build());
    }

    @Test
    @DisplayName("Deudas: convierte la respuesta del BCRA")
    void deudasOk() {
        String json = """
                {"status":200,"results":{"identificacion":20123456789,"denominacion":"PEREZ JUAN",
                 "periodos":[{"periodo":"202608","entidades":[
                   {"entidad":"BANCO DE LA NACION ARGENTINA","situacion":1,"fechaSit1":null,"monto":120.0,
                    "diasAtrasoPago":0,"refinanciaciones":false,"recategorizacionOblig":false,
                    "situacionJuridica":false,"irrecDisposicionTecnica":false,"enRevision":false,"procesoJud":false}]}]}}
                """;
        server.expect(requestTo(BASE + CUIT)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        Optional<BcraDeudorDTO> deudor = client.obtenerDeudas(CUIT);

        assertAll(
                () -> assertTrue(deudor.isPresent()),
                () -> assertEquals("PEREZ JUAN", deudor.get().getDenominacion()),
                () -> assertEquals(1, deudor.get().getPeriodos().get(0).getEntidades().get(0).getSituacion()));
    }

    @Test
    @DisplayName("404 del BCRA significa 'sin registros', no un error")
    void sinRegistros() {
        String json = "{\"status\":404,\"errorMessages\":[\"No se encontró datos para la identificación ingresada.\"]}";
        server.expect(requestTo(BASE + "ChequesRechazados/" + CUIT))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(json));

        Optional<BcraChequesResultadoDTO> cheques = client.obtenerChequesRechazados(CUIT);

        assertTrue(cheques.isEmpty());
    }

    @Test
    @DisplayName("Un 500 del BCRA se propaga como RestClientException")
    void errorDelServidor() {
        server.expect(requestTo(BASE + CUIT)).andRespond(withServerError());
        assertThrows(RestClientException.class, () -> client.obtenerDeudas(CUIT));
    }
}
