package com.rovedasa.contratos.config;

import com.rovedasa.commons.security.KeycloakRoleConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * OAuth2 Resource Server: valida el JWT emitido por Keycloak y mapea realm_access.roles a ROLE_XXX con el
 * KeycloakRoleConverter de commons. Es la misma en todos los perfiles (Alcance, sección 11): también en
 * "local" hace falta un token de Keycloak.
 *
 * - Públicos: solo /actuator/health y Swagger/OpenAPI (Alcance, sección 10).
 * - /actuator/** (info, metrics, loggers): solo ADMINISTRADOR. loggers permite cambiar niveles de log con POST.
 * - El resto exige estar autenticado; cada endpoint define su rol con @PreAuthorize.
 * - 401 y 403 salen con el ApiError de commons (y correlationId), igual que en el catálogo.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private static final String[] RUTAS_PUBLICAS = {
            "/actuator/health", "/actuator/health/**",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    private final HandlerExceptionResolver resolutorDeExcepciones;

    public SecurityConfig(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolutorDeExcepciones) {
        this.resolutorDeExcepciones = resolutorDeExcepciones;
    }

    @Bean
    SecurityFilterChain cadenaFiltros(HttpSecurity http, KeycloakRoleConverter keycloakRoleConverter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers(RUTAS_PUBLICAS).permitAll()
                        .requestMatchers("/actuator/**").hasRole(ROL_ADMINISTRADOR)
                        .anyRequest().authenticated())
                // Sin token: lo corta el ExceptionTranslationFilter con este entry point
                .exceptionHandling(excepciones -> excepciones
                        .authenticationEntryPoint(noAutenticadoComoApiError())
                        .accessDeniedHandler(accesoDenegadoComoApiError()))
                // Token inválido o vencido: lo corta el filtro de bearer token, que usa su propio entry point
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(noAutenticadoComoApiError())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter)))
                .build();
    }

    /*
     * Los 401 y 403 de la cadena de filtros saldrían con el body vacío. Se delegan a los @RestControllerAdvice
     * (GlobalExceptionHandler de commons) para que salgan con el mismo ApiError que el resto de los errores.
     */

    // Primero el entry point estándar de Spring: pone el header WWW-Authenticate: Bearer (RFC 6750),
    // con el detalle del error si el token era inválido. Después el handler escribe el ApiError.
    private AuthenticationEntryPoint noAutenticadoComoApiError() {
        BearerTokenAuthenticationEntryPoint entryPointBearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, excepcion) -> {
            entryPointBearer.commence(request, response, excepcion);
            resolutorDeExcepciones.resolveException(request, response, null, excepcion);
        };
    }

    private AccessDeniedHandler accesoDenegadoComoApiError() {
        return (request, response, excepcion) ->
                resolutorDeExcepciones.resolveException(request, response, null, excepcion);
    }
}
