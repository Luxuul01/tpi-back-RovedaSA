package com.rovedasa.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@AutoConfigureWebTestClient
class ApiGatewayContextTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private SecurityWebFilterChain securityWebFilterChain;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void contextLoadsConWebFluxConversionServiceYSeguridad() {
        assertNotNull(routeLocator);
        assertNotNull(securityWebFilterChain);
    }

    @Test
    void corsPreflight_permiteOrigenExternoEnGateway() {
        webTestClient.options().uri("http://localhost:8080/api/propiedades")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    void swaggerConfig_esPublicoYExponeAmbosMicroservicios() {
        webTestClient.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[?(@.name=='ms-catalogo-inmobiliario')].url").isEqualTo("/v3/api-docs/catalogo")
                .jsonPath("$.urls[?(@.name=='ms-contratos')].url").isEqualTo("/v3/api-docs/contratos");
    }
}
