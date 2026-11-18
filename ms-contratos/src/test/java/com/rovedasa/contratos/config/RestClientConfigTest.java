package com.rovedasa.contratos.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Cliente hacia el catálogo con los dos interceptores (X-Correlation-ID y token de servicio), contra un
 * catálogo y un Keycloak simulados con MockRestServiceServer (Apunte 18).
 */
class RestClientConfigTest {

    private static final String URL_BASE = "http://localhost:8081/api";
    private static final String TOKEN_URI = ProveedorTokenServicioTest.TOKEN_URI;

    private RestClientConfig restClientConfig;
    private MockRestServiceServer catalogo;
    private MockRestServiceServer keycloak;
    private RestClient catalogoRestClient;
    private ProveedorTokenServicio proveedor;

    @BeforeEach
    void setUp() {
        restClientConfig = new RestClientConfig();

        RestClient.Builder builderKeycloak = RestClient.builder().baseUrl(TOKEN_URI);
        keycloak = MockRestServiceServer.bindTo(builderKeycloak).build();
        proveedor = new ProveedorTokenServicio(builderKeycloak.build(), "backend-services", "secreto",
                new ProveedorTokenServicioTest.RelojAjustable(Instant.parse("2026-11-17T12:00:00Z")));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(URL_BASE)
                .requestInterceptor(restClientConfig.crearInterceptorCatalogo())
                .requestInterceptor(RestClientConfig.crearInterceptorTokenServicio(proveedor));
        catalogo = MockRestServiceServer.bindTo(builder).build();
        catalogoRestClient = builder.build();
    }

    @AfterEach
    void limpiarContextos() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Usa el token de servicio (no el del usuario) y propaga X-Correlation-ID")
    void usaElTokenDeServicioYPropagaCorrelationId() {
        MDC.put(RestClientConfig.CORRELATION_ID_MDC_KEY, "req-corr-123");
        // Aunque haya un usuario autenticado, al catálogo va la cuenta de servicio
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                crearJwtPrueba("token-del-usuario"), List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
        keycloak.expect(once(), requestTo(TOKEN_URI))
                .andRespond(ProveedorTokenServicioTest.respuestaToken("token-servicio", 300));
        catalogo.expect(requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RestClientConfig.CORRELATION_ID_HEADER, "req-corr-123"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-servicio"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get().uri("/propiedades/10").retrieve().toBodilessEntity();

        catalogo.verify();
        keycloak.verify();
    }

    @Test
    @DisplayName("Funciona sin usuario autenticado (ajuste programado): pone igual el token de servicio")
    void funcionaSinUsuarioAutenticado() {
        keycloak.expect(once(), requestTo(TOKEN_URI))
                .andRespond(ProveedorTokenServicioTest.respuestaToken("token-servicio", 300));
        catalogo.expect(requestTo(URL_BASE + "/propiedades/10/estado"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-servicio"))
                .andExpect(headerDoesNotExist(RestClientConfig.CORRELATION_ID_HEADER))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.patch().uri("/propiedades/10/estado").body("{}").retrieve().toBodilessEntity();

        catalogo.verify();
    }

    @Test
    @DisplayName("Dos llamadas al catálogo usan el mismo token: Keycloak se consulta una sola vez")
    void reutilizaElTokenEntreLlamadas() {
        keycloak.expect(once(), requestTo(TOKEN_URI))
                .andRespond(ProveedorTokenServicioTest.respuestaToken("token-servicio", 300));
        catalogo.expect(twice(), requestTo(URL_BASE + "/personas/5"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-servicio"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get().uri("/personas/5").retrieve().toBodilessEntity();
        catalogoRestClient.get().uri("/personas/5").retrieve().toBodilessEntity();

        catalogo.verify();
        keycloak.verify();
    }

    @Test
    @DisplayName("Si el catálogo rechaza el token (401), pide uno nuevo y reintenta una sola vez")
    void renuevaElTokenYReintentaAnteUn401() {
        MDC.put(RestClientConfig.CORRELATION_ID_MDC_KEY, "req-corr-401");
        keycloak.expect(once(), requestTo(TOKEN_URI))
                .andRespond(ProveedorTokenServicioTest.respuestaToken("token-viejo", 300));
        keycloak.expect(once(), requestTo(TOKEN_URI))
                .andRespond(ProveedorTokenServicioTest.respuestaToken("token-nuevo", 300));
        catalogo.expect(once(), requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-viejo"))
                .andRespond(withUnauthorizedRequest());
        catalogo.expect(once(), requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-nuevo"))
                .andExpect(header(RestClientConfig.CORRELATION_ID_HEADER, "req-corr-401"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get().uri("/propiedades/10").retrieve().toBodilessEntity();

        catalogo.verify();
        keycloak.verify();
    }

    @Test
    @DisplayName("No envía X-Correlation-ID en blanco ni sobrescribe un header Authorization explícito")
    void noDebeSobrescribirAuthorizationExplicitoNiEnviarCorrelationIdEnBlanco() {
        MDC.put(RestClientConfig.CORRELATION_ID_MDC_KEY, "   ");
        catalogo.expect(requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(headerDoesNotExist(RestClientConfig.CORRELATION_ID_HEADER))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-explicito"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get()
                .uri("/propiedades/10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-explicito")
                .retrieve()
                .toBodilessEntity();

        catalogo.verify();
        // Keycloak no se consultó
        keycloak.verify();
    }

    @Test
    @DisplayName("Los beans catalogoRestClient y proveedorTokenServicio se construyen sin llamar a Keycloak")
    void debeConstruirLosBeans() {
        ProveedorTokenServicio deConfig = restClientConfig.proveedorTokenServicio(
                RestClient.builder(), TOKEN_URI, "backend-services", "secreto", 3000, 5000);
        RestClient cliente = restClientConfig.catalogoRestClient(RestClient.builder(), deConfig, URL_BASE, 3000, 12000);
        assertNotNull(deConfig);
        assertNotNull(cliente);
    }

    private static Jwt crearJwtPrueba(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("sub", "usuario-test")
                .build();
    }
}
