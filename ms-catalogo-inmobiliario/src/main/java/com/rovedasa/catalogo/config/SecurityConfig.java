package com.rovedasa.catalogo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad del microservicio según el perfil activo:
 * - local: todo abierto, para probar sin levantar Keycloak.
 * - cualquier otro: OAuth2 Resource Server validando el JWT de Keycloak.
 */
@Slf4j
@Configuration
public class SecurityConfig {

    @Configuration
    @Profile("local")
    static class SeguridadPerfilLocal {

        @Bean
        SecurityFilterChain cadenaFiltrosLocal(HttpSecurity http) throws Exception {
            log.warn("Seguridad DESACTIVADA por perfil 'local': todos los endpoints quedan abiertos sin autenticación");
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(autorizacion -> autorizacion.anyRequest().permitAll())
                    .build();
        }
    }

    // @EnableMethodSecurity solo acá: en local no hay usuario autenticado y los @PreAuthorize fallarían
    @Configuration
    @Profile("!local")
    @EnableMethodSecurity
    static class SeguridadPerfilProtegido {

        @Bean
        SecurityFilterChain cadenaFiltrosProtegida(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(autorizacion -> autorizacion
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2
                            .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRolesConverter())))
                    .build();
        }
    }
}
