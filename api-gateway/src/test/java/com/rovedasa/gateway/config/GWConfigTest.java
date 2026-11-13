package com.rovedasa.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GWConfigTest {

    private final GWConfig gwConfig = new GWConfig();

    @Test
    void grantedAuthoritiesExtractor_extraeRolesConPrefijoRole() {
        Jwt jwt = Jwt.withTokenValue("token-prueba")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("administrador", "PROPIETARIO")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        Converter<Jwt, Mono<AbstractAuthenticationToken>> converter = gwConfig.grantedAuthoritiesExtractor();
        AbstractAuthenticationToken auth = converter.convert(jwt).block();

        assertNotNull(auth);
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertEquals(Set.of("ROLE_ADMINISTRADOR", "ROLE_PROPIETARIO"), authorities);
    }

    @Test
    void grantedAuthoritiesExtractor_sinRealmAccessDevuelveListaVacia() {
        Jwt jwt = Jwt.withTokenValue("token-sin-roles")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-000000000003")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        Converter<Jwt, Mono<AbstractAuthenticationToken>> converter = gwConfig.grantedAuthoritiesExtractor();
        AbstractAuthenticationToken auth = converter.convert(jwt).block();

        assertNotNull(auth);
        assertTrue(auth.getAuthorities().isEmpty());
    }

    @Test
    void configurarRutas_registraCincoRutasEsperadas() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(PathRoutePredicateFactory.class);
        context.refresh();
        RouteLocatorBuilder builder = new RouteLocatorBuilder(context);

        RouteLocator locator = gwConfig.configurarRutas(
                builder,
                "http://localhost:8081",
                "http://localhost:8082");

        List<Route> routes = locator.getRoutes().collectList().block();
        assertNotNull(routes);
        assertEquals(5, routes.size());
    }
}
