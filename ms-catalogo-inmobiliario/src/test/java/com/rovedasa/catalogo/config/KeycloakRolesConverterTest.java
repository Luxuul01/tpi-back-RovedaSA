package com.rovedasa.catalogo.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakRolesConverterTest {

    private final KeycloakRolesConverter converter = new KeycloakRolesConverter();

    @Test
    void debeMapearRolesDelRealmAAuthoritiesConPrefijoRole() {
        Jwt jwt = jwtBase()
                .claim("realm_access", Map.of("roles", List.of("ADMINISTRADOR")))
                .build();

        AbstractAuthenticationToken autenticacion = converter.convert(jwt);

        List<String> authorities = autenticacion.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertEquals(List.of("ROLE_ADMINISTRADOR"), authorities);
        assertEquals("usuario-keycloak-123", autenticacion.getName());
    }

    @Test
    void noDebeFallarSiElJwtNoTraeRealmAccess() {
        Jwt jwt = jwtBase().build();

        AbstractAuthenticationToken autenticacion = converter.convert(jwt);

        assertTrue(autenticacion.getAuthorities().isEmpty());
        assertEquals("usuario-keycloak-123", autenticacion.getName());
    }

    private Jwt.Builder jwtBase() {
        return Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .subject("usuario-keycloak-123");
    }
}
