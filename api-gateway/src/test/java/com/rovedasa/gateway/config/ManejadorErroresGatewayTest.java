package com.rovedasa.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rovedasa.gateway.filter.CorrelationIdWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapeo de los errores de conexión y de validación del token que resuelve el propio Gateway, llamando al
 * manejador directamente. El ruteo completo (404, 503 con el servicio apagado) está en GatewayRuteoErroresTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class ManejadorErroresGatewayTest {

    private final ManejadorErroresGateway manejador = new ManejadorErroresGateway();
    private final ObjectMapper mapper = new ObjectMapper();

    private MockServerWebExchange exchange() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/api/contratos").build());
        ex.getAttributes().put(CorrelationIdWebFilter.ATRIBUTO_CORRELATION_ID, "req-test-123");
        return ex;
    }

    private JsonNode cuerpo(MockServerWebExchange ex) throws Exception {
        return mapper.readTree(ex.getResponse().getBodyAsString().block());
    }

    @Test
    @DisplayName("El microservicio corta la conexión a mitad del request (PrematureCloseException) -> 502 con ApiError")
    void conexionCortadaPorElMicroservicioDa502(CapturedOutput salida) throws Exception {
        MockServerWebExchange ex = exchange();

        manejador.handle(ex, PrematureCloseException.TEST_EXCEPTION).block();

        JsonNode body = cuerpo(ex);
        assertAll(
                () -> assertEquals(HttpStatus.BAD_GATEWAY, ex.getResponse().getStatusCode()),
                () -> assertEquals(502, body.get("status").asInt()),
                () -> assertEquals("Bad Gateway", body.get("error").asText()),
                () -> assertEquals("/api/contratos", body.get("path").asText()),
                () -> assertEquals("req-test-123", body.get("correlationId").asText()),
                () -> assertTrue(salida.getAll().contains("cortó la conexión para POST /api/contratos "
                        + "(correlationId=req-test-123)")));
    }

    @Test
    @DisplayName("Conexión reseteada por el microservicio (SocketException) -> 502")
    void conexionReseteadaDa502() throws Exception {
        MockServerWebExchange ex = exchange();

        manejador.handle(ex, new SocketException("Connection reset")).block();

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getResponse().getStatusCode());
        assertEquals(502, cuerpo(ex).get("status").asInt());
    }

    @Test
    @DisplayName("Microservicio que no acepta conexiones (ConnectException) sigue en 503, con el correlationId en el log")
    void servicioInaccesibleSigueEn503YLogueaElCorrelationId(CapturedOutput salida) throws Exception {
        MockServerWebExchange ex = exchange();

        manejador.handle(ex, new ConnectException("Connection refused")).block();

        assertAll(
                () -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getResponse().getStatusCode()),
                () -> assertEquals(503, cuerpo(ex).get("status").asInt()),
                () -> assertTrue(salida.getAll().contains("no disponible para POST /api/contratos "
                        + "(correlationId=req-test-123)")));
    }

    @Test
    @DisplayName("Keycloak no entrega las claves para validar el token (AuthenticationServiceException) -> 503")
    void servidorDeIdentidadCaidoDa503(CapturedOutput salida) throws Exception {
        MockServerWebExchange ex = exchange();

        manejador.handle(ex, new AuthenticationServiceException("Couldn't retrieve remote JWK set")).block();

        JsonNode body = cuerpo(ex);
        assertAll(
                () -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getResponse().getStatusCode()),
                () -> assertEquals(503, body.get("status").asInt()),
                () -> assertEquals("No se pudo validar el token: el servidor de identidad no responde",
                        body.get("message").asText()),
                () -> assertEquals("req-test-123", body.get("correlationId").asText()),
                () -> assertTrue(salida.getAll().contains("(correlationId=req-test-123)")));
    }

    @Test
    @DisplayName("Keycloak apagado al bajar el JWKS (IllegalStateException con WebClientRequestException) -> 503 "
            + "del servidor de identidad, no el del servicio de destino")
    void keycloakApagadoAlBajarElJwksDa503DelServidorDeIdentidad() throws Exception {
        MockServerWebExchange ex = exchange();
        WebClientRequestException sinConexion = new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.GET,
                URI.create("http://keycloak:8080/realms/rovedasa-realm/protocol/openid-connect/certs"),
                new HttpHeaders());

        manejador.handle(ex, new IllegalStateException("Could not obtain the keys", sinConexion)).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getResponse().getStatusCode());
        assertEquals("No se pudo validar el token: el servidor de identidad no responde",
                cuerpo(ex).get("message").asText());
    }

    @Test
    @DisplayName("Keycloak responde 5xx al bajar el JWKS -> 503 del servidor de identidad (no un 500)")
    void keycloakConErrorAlBajarElJwksDa503() throws Exception {
        MockServerWebExchange ex = exchange();
        WebClientResponseException respuesta5xx = WebClientResponseException.create(
                503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);

        manejador.handle(ex, new IllegalStateException("Could not obtain the keys", respuesta5xx)).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getResponse().getStatusCode());
        assertEquals("No se pudo validar el token: el servidor de identidad no responde",
                cuerpo(ex).get("message").asText());
    }

    @Test
    @DisplayName("Error inesperado -> 500 sin detalle, con el correlationId en el log")
    void errorInesperadoDa500ConElCorrelationIdEnElLog(CapturedOutput salida) throws Exception {
        MockServerWebExchange ex = exchange();

        manejador.handle(ex, new IllegalStateException("detalle interno")).block();

        assertAll(
                () -> assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getResponse().getStatusCode()),
                () -> assertFalse(cuerpo(ex).get("message").asText().contains("detalle interno")),
                () -> assertTrue(salida.getAll().contains("Error inesperado en el Gateway para POST /api/contratos "
                        + "(correlationId=req-test-123)")));
    }
}
