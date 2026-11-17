package com.rovedasa.contratos.controller;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Identidad mínima extraída del JWT: el sub de Keycloak y el rol de negocio.
 * Keycloak incluye roles técnicos (default-roles-*, offline_access, uma_authorization)
 * en realm_access.roles, por eso se buscan explícitamente los roles de negocio y no
 * se toma el primero de la colección.
 */
record UsuarioToken(String sub, String rol) {

    private static final List<String> ROLES_NEGOCIO =
            List.of("ADMINISTRADOR", "PROPIETARIO", "INQUILINO");

    static UsuarioToken desde(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new OperacionInvalidaException("No se pudo obtener la identidad del token JWT");
        }
        String sub = jwt.getClaimAsString("sub");
        Set<String> roles = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .collect(Collectors.toSet());
        String rol = ROLES_NEGOCIO.stream()
                .filter(roles::contains)
                .findFirst()
                .orElseThrow(() -> new OperacionInvalidaException(
                        "El token no tiene un rol de negocio habilitado (ADMINISTRADOR, PROPIETARIO o INQUILINO)"));
        return new UsuarioToken(sub, rol);
    }
}
