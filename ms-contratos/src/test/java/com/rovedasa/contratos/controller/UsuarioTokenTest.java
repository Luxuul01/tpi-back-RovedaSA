package com.rovedasa.contratos.controller;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsuarioTokenTest {

    @Test
    @DisplayName("Ignora los roles técnicos de Keycloak y toma el rol de negocio")
    void debeIgnorarRolesTecnicos() {
        UsuarioToken usuario = UsuarioToken.desde(token(List.of(
                "DEFAULT-ROLES-ROVEDASA-REALM", "OFFLINE_ACCESS", "UMA_AUTHORIZATION", "INQUILINO")));

        assertEquals("sub-123", usuario.sub());
        assertEquals("INQUILINO", usuario.rol());
    }

    @Test
    @DisplayName("Prioriza ADMINISTRADOR si el usuario tiene más de un rol de negocio")
    void debePriorizarAdministrador() {
        UsuarioToken usuario = UsuarioToken.desde(token(List.of("PROPIETARIO", "ADMINISTRADOR")));

        assertEquals("ADMINISTRADOR", usuario.rol());
    }

    @Test
    @DisplayName("Rechaza tokens sin rol de negocio")
    void debeRechazarTokenSinRolDeNegocio() {
        JwtAuthenticationToken authentication = token(List.of("OFFLINE_ACCESS"));

        assertThrows(OperacionInvalidaException.class, () -> UsuarioToken.desde(authentication));
    }

    @Test
    @DisplayName("Rechaza una autenticación ausente")
    void debeRechazarAutenticacionAusente() {
        assertThrows(OperacionInvalidaException.class, () -> UsuarioToken.desde(null));
    }

    private static JwtAuthenticationToken token(List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "none")
                .claim("sub", "sub-123")
                .build();
        return new JwtAuthenticationToken(jwt, roles.stream()
                .map(rol -> new SimpleGrantedAuthority("ROLE_" + rol))
                .toList());
    }
}
