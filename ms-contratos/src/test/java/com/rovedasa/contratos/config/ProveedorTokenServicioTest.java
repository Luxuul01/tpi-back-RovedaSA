package com.rovedasa.contratos.config;

import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class ProveedorTokenServicioTest {

    static final String TOKEN_URI = "http://keycloak:8080/realms/rovedasa-realm/protocol/openid-connect/token";

    private MockRestServiceServer keycloak;
    private RestClient clienteKeycloak;
    private RelojAjustable reloj;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(TOKEN_URI);
        keycloak = MockRestServiceServer.bindTo(builder).build();
        clienteKeycloak = builder.build();
        reloj = new RelojAjustable(Instant.parse("2026-11-17T12:00:00Z"));
    }

    @Test
    @DisplayName("Pide el token con client credentials una sola vez y lo reutiliza mientras está vigente")
    void pideElTokenUnaVezYLoReutiliza() {
        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("client_id", "backend-services");
        formulario.add("client_secret", "secreto-de-prueba");
        keycloak.expect(once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(formulario))
                .andRespond(respuestaToken("token-1", 300));
        ProveedorTokenServicio proveedor = proveedor("secreto-de-prueba");

        assertEquals("token-1", proveedor.obtenerToken());
        reloj.avanzar(Duration.ofSeconds(200));
        assertEquals("token-1", proveedor.obtenerToken());

        keycloak.verify();
    }

    @Test
    @DisplayName("Renueva el token cuando le quedan menos de 30 segundos de vida")
    void renuevaElTokenAntesDeQueVenza() {
        keycloak.expect(once(), requestTo(TOKEN_URI)).andRespond(respuestaToken("token-1", 300));
        keycloak.expect(once(), requestTo(TOKEN_URI)).andRespond(respuestaToken("token-2", 300));
        ProveedorTokenServicio proveedor = proveedor("secreto");

        assertEquals("token-1", proveedor.obtenerToken());
        reloj.avanzar(Duration.ofSeconds(269));
        assertEquals("token-1", proveedor.obtenerToken());
        reloj.avanzar(Duration.ofSeconds(1));
        assertEquals("token-2", proveedor.obtenerToken());

        keycloak.verify();
    }

    @Test
    @DisplayName("Después de invalidar, pide un token nuevo aunque el anterior no haya vencido")
    void invalidarFuerzaUnTokenNuevo() {
        keycloak.expect(once(), requestTo(TOKEN_URI)).andRespond(respuestaToken("token-1", 300));
        keycloak.expect(once(), requestTo(TOKEN_URI)).andRespond(respuestaToken("token-2", 300));
        ProveedorTokenServicio proveedor = proveedor("secreto");

        assertEquals("token-1", proveedor.obtenerToken());
        proveedor.invalidar();
        assertEquals("token-2", proveedor.obtenerToken());

        keycloak.verify();
    }

    @Test
    @DisplayName("Si Keycloak rechaza las credenciales -> CatalogoNoDisponibleException (503)")
    void credencialesRechazadasDanCatalogoNoDisponible() {
        keycloak.expect(once(), requestTo(TOKEN_URI)).andRespond(withUnauthorizedRequest()
                .body("{\"error\":\"unauthorized_client\"}").contentType(MediaType.APPLICATION_JSON));
        ProveedorTokenServicio proveedor = proveedor("secreto-equivocado");

        CatalogoNoDisponibleException ex = assertThrows(CatalogoNoDisponibleException.class,
                proveedor::obtenerToken);
        assertTrue(ex.getMessage().contains("401"), ex.getMessage());
        assertTrue(!ex.getMessage().contains("secreto-equivocado"));
    }

    @Test
    @DisplayName("Si Keycloak está caído -> CatalogoNoDisponibleException (503)")
    void keycloakCaidoDaCatalogoNoDisponible() {
        keycloak.expect(once(), requestTo(TOKEN_URI)).andRespond(withStatus(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        ProveedorTokenServicio proveedor = proveedor("secreto");

        assertThrows(CatalogoNoDisponibleException.class, proveedor::obtenerToken);
    }

    @Test
    @DisplayName("Sin secreto configurado no llama a Keycloak e indica la variable que falta")
    void sinSecretoNoLlamaAKeycloak() {
        ProveedorTokenServicio proveedor = proveedor("");

        CatalogoNoDisponibleException ex = assertThrows(CatalogoNoDisponibleException.class,
                proveedor::obtenerToken);
        assertTrue(ex.getMessage().contains("KEYCLOAK_CLIENT_SECRET"));
        keycloak.verify();
    }

    private ProveedorTokenServicio proveedor(String secreto) {
        return new ProveedorTokenServicio(clienteKeycloak, "backend-services", secreto, reloj);
    }

    static org.springframework.test.web.client.response.DefaultResponseCreator respuestaToken(String token,
            int expiraEnSegundos) {
        return withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiraEnSegundos
                + ",\"token_type\":\"Bearer\",\"scope\":\"profile email\"}", MediaType.APPLICATION_JSON);
    }

    /** Reloj que los tests pueden adelantar. */
    static class RelojAjustable extends java.time.Clock {
        private Instant ahora;

        RelojAjustable(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration duracion) {
            ahora = ahora.plus(duracion);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
