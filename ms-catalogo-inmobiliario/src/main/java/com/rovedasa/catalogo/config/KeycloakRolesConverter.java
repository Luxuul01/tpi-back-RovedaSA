package com.rovedasa.catalogo.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Convierte el JWT emitido por Keycloak en un token de autenticación de Spring Security:
 * cada rol del claim realm_access.roles se mapea a una authority "ROLE_" + rol, y el
 * principal name es el claim sub (keycloakUserId).
 *
 * TODO: esta clase debería migrar a inmobiliaria-commons para que ms-contratos la reutilice
 * (pendiente de proponer al Tech Lead).
 */
public class KeycloakRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_ROLES = "roles";
    private static final String PREFIJO_ROL = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, extraerAuthorities(jwt), jwt.getSubject());
    }

    private Collection<GrantedAuthority> extraerAuthorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(CLAIM_REALM_ACCESS);
        if (realmAccess == null || !(realmAccess.get(CLAIM_ROLES) instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .map(rol -> (GrantedAuthority) new SimpleGrantedAuthority(PREFIJO_ROL + rol))
                .collect(Collectors.toUnmodifiableSet());
    }
}
