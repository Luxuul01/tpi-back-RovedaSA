package com.rovedasa.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebFluxSecurity
public class GWConfig {

    @Bean
    public RouteLocator configurarRutas(RouteLocatorBuilder builder,
            @Value("${rovedasa-gw.url-microservicio-catalogo}") String uriCatalogo,
            @Value("${rovedasa-gw.url-microservicio-contratos}") String uriContratos) {
        return builder.routes()
                // 1. Ruteo a Catálogo: Propiedades
                .route(p -> p.path("/api/propiedades/**").uri(uriCatalogo))
                // 2. Ruteo a Catálogo: Personas y evaluación de garantes
                .route(p -> p.path("/api/personas/**").uri(uriCatalogo))
                // 3. Ruteo a Contratos: Contratos
                .route(p -> p.path("/api/contratos/**").uri(uriContratos))
                // 4. Ruteo a Contratos: Cuotas
                .route(p -> p.path("/api/cuotas/**").uri(uriContratos))
                // 5. Ruteo a Contratos: Índices BCRA / ICL / Caché
                .route(p -> p.path("/api/indices/**").uri(uriContratos))
                // 6. Documentación OpenAPI unificada para Swagger UI del Gateway
                .route("openapi-catalogo", p -> p.path("/v3/api-docs/catalogo")
                        .filters(f -> f.rewritePath("/v3/api-docs/catalogo", "/v3/api-docs"))
                        .uri(uriCatalogo))
                .route("openapi-contratos", p -> p.path("/v3/api-docs/contratos")
                        .filters(f -> f.rewritePath("/v3/api-docs/contratos", "/v3/api-docs"))
                        .uri(uriContratos))
                .build();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMINISTRADOR")
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/**").hasRole("ADMINISTRADOR")
                        .pathMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMINISTRADOR")
                        .pathMatchers(HttpMethod.PATCH, "/api/**").hasRole("ADMINISTRADOR")
                        .pathMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMINISTRADOR")
                        .pathMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMINISTRADOR", "PROPIETARIO", "INQUILINO")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(grantedAuthoritiesExtractor())))
                .build();
    }

    /**
     * Habilita CORS global en el Gateway para clientes web locales y Swagger UI sin usar credenciales por cookie.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Correlation-ID", "Location"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Convierte los roles de Keycloak ({@code realm_access.roles}) en {@code GrantedAuthority} con prefijo {@code ROLE_}.
     * No se expone como {@code @Bean} para que {@code webFluxConversionService} no lo registre como conversor HTTP genérico.
     */
    Converter<Jwt, Mono<AbstractAuthenticationToken>> grantedAuthoritiesExtractor() {
        return new Converter<Jwt, Mono<AbstractAuthenticationToken>>() {
            @Override
            public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
                Map<String, Object> realmAccess = jwt.getClaim("realm_access");
                Collection<GrantedAuthority> authorities = Collections.emptyList();

                if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
                    authorities = roles.stream()
                            .map(Object::toString)
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                            .collect(Collectors.toList());
                }

                return Mono.just(new JwtAuthenticationToken(jwt, authorities));
            }
        };
    }
}