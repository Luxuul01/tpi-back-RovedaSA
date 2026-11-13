package com.rovedasa.catalogo.config;

import com.rovedasa.commons.security.KeycloakRoleConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * OAuth2 Resource Server: valida el JWT emitido por Keycloak y mapea realm_access.roles a ROLE_XXX.
 * El KeycloakRoleConverter de commons ya devuelve el JwtAuthenticationToken completo, por eso se pasa
 * directo a jwtAuthenticationConverter() en lugar de envolverlo en un JwtAuthenticationConverter.
 *
 * Dos capas de autorización (RN-16):
 *   1. Por URL (esta clase): POST, PUT, PATCH y DELETE sobre /api/** solo para ADMINISTRADOR. Se evalúa en
 *      la cadena de filtros, antes de que Spring MVC lea y valide el body: un rol sin permiso recibe 403
 *      aunque el body sea inválido. Los GET solo exigen estar autenticado.
 *   2. Por método: cada endpoint mantiene su @PreAuthorize (por ejemplo, GET /propias es solo PROPIETARIO).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";
    private static final String RUTAS_API = "/api/**";

    // Alcance, sección 10: solo Swagger/OpenAPI y el health check son públicos
    private static final String[] RUTAS_PUBLICAS = {
            "/actuator/health", "/actuator/health/**",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    private final HandlerExceptionResolver resolutorDeExcepciones;

    public SecurityConfig(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolutorDeExcepciones) {
        this.resolutorDeExcepciones = resolutorDeExcepciones;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(RUTAS_PUBLICAS).permitAll()
                .requestMatchers(HttpMethod.POST, RUTAS_API).hasRole(ROL_ADMINISTRADOR)
                .requestMatchers(HttpMethod.PUT, RUTAS_API).hasRole(ROL_ADMINISTRADOR)
                .requestMatchers(HttpMethod.PATCH, RUTAS_API).hasRole(ROL_ADMINISTRADOR)
                .requestMatchers(HttpMethod.DELETE, RUTAS_API).hasRole(ROL_ADMINISTRADOR)
                .anyRequest().authenticated()
            )
            .exceptionHandling(excepciones -> excepciones.accessDeniedHandler(accesoDenegadoComoApiError()))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRoleConverter()))
            );
        return http.build();
    }

    /**
     * Un 403 de la cadena de filtros saldría con el body vacío. Se delega al @RestControllerAdvice
     * (CatalogoExceptionHandler) para que tenga el mismo ApiError que el 403 de un @PreAuthorize.
     */
    private AccessDeniedHandler accesoDenegadoComoApiError() {
        return (request, response, excepcion) ->
                resolutorDeExcepciones.resolveException(request, response, null, excepcion);
    }
}
