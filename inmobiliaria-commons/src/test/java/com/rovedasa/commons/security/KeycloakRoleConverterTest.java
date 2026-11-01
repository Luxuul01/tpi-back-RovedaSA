package com.rovedasa.commons.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakRoleConverterTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter();

    @Test
    void debeMapearRolesConPrefijoRoleCorrectamente() {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("sub", "00000000-0000-0000-0000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("ADMINISTRADOR", "PROPIETARIO")))
                .build();

        AbstractAuthenticationToken authToken = converter.convert(jwt);

        assertNotNull(authToken);
        Set<String> authorities = authToken.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertTrue(authorities.contains("ROLE_ADMINISTRADOR"), "Debe contener ROLE_ADMINISTRADOR");
        assertTrue(authorities.contains("ROLE_PROPIETARIO"), "Debe contener ROLE_PROPIETARIO");
        assertEquals(2, authorities.size());
    }

    @Test
    void debeManejarJwtSinRolesSinLanzarExcepcion() {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("sub", "00000000-0000-0000-0000-000000000001")
                .build();

        AbstractAuthenticationToken authToken = converter.convert(jwt);

        assertNotNull(authToken);
        assertTrue(authToken.getAuthorities().isEmpty(), "No debe tener roles asignados");
    }
}
