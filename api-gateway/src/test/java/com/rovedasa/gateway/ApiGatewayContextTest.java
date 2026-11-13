package com.rovedasa.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class ApiGatewayContextTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private SecurityWebFilterChain securityWebFilterChain;

    @Test
    void contextLoadsConWebFluxConversionServiceYSeguridad() {
        assertNotNull(routeLocator);
        assertNotNull(securityWebFilterChain);
    }
}
