package com.rovedasa.contratos.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientConfigTest {

    private static final String URL_BASE = "http://localhost:8081/api";

    private RestClientConfig restClientConfig;
    private MockRestServiceServer server;
    private RestClient catalogoRestClient;

    @BeforeEach
    void setUp() {
        restClientConfig = new RestClientConfig();
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(URL_BASE)
                .requestInterceptor(restClientConfig.crearInterceptorCatalogo());
        server = MockRestServiceServer.bindTo(builder).build();
        catalogoRestClient = builder.build();
    }

    @AfterEach
    void limpiarContextos() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Propaga X-Correlation-ID y Authorization Bearer cuando MDC y JWT están presentes")
    void debePropagarCorrelationIdYTokenJwtCuandoEstanPresentes() {
        MDC.put(RestClientConfig.CORRELATION_ID_MDC_KEY, "req-corr-123");
        Jwt jwt = crearJwtPrueba("token-jwt-prueba");
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));

        server.expect(requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RestClientConfig.CORRELATION_ID_HEADER, "req-corr-123"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-jwt-prueba"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get()
                .uri("/propiedades/10")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("Ejecuta sin errores y sin headers cuando MDC y SecurityContextHolder están vacíos")
    void debeEjecutarSinHeadersCuandoContextosEstanVacios() {
        server.expect(requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(RestClientConfig.CORRELATION_ID_HEADER))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get()
                .uri("/propiedades/10")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("No envía X-Correlation-ID en blanco ni sobrescribe un header Authorization existente")
    void noDebeSobrescribirAuthorizationExistenteNiEnviarCorrelationIdEnBlanco() {
        MDC.put(RestClientConfig.CORRELATION_ID_MDC_KEY, "   ");
        Jwt jwt = crearJwtPrueba("token-del-contexto");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        server.expect(requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(RestClientConfig.CORRELATION_ID_HEADER))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-explicito"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get()
                .uri("/propiedades/10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-explicito")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("Ignora autenticaciones que no son JwtAuthenticationToken")
    void debeIgnorarAutenticacionNoJwt() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("usuario", "clave"));

        server.expect(requestTo(URL_BASE + "/propiedades/10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        catalogoRestClient.get()
                .uri("/propiedades/10")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("El bean catalogoRestClient se construye correctamente con timeouts y URL base")
    void debeConstruirBeanCatalogoRestClient() {
        RestClient cliente = restClientConfig.catalogoRestClient(
                RestClient.builder(), URL_BASE, 3000, 5000);
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

