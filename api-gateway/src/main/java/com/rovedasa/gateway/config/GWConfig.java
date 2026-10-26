package com.rovedasa.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GWConfig {
    @Bean
    public RouteLocator configurarRutas(RouteLocatorBuilder builder,
            @Value("${rovedasa-gw.url-microservicio-catalogo}") String uriCatalogo,
            @Value("${rovedasa-gw.url-microservicio-contratos}") String uriContratos) {
        return builder.routes()
                // 1. Ruteo a Catálogo: Propiedades
                .route(p -> p.path("/api/propiedades/**").uri(uriCatalogo))
                // 2. Ruteo a Catálogo: Personas
                .route(p -> p.path("/api/personas/**").uri(uriCatalogo))
                // 3. Ruteo a Contratos: Contratos
                .route(p -> p.path("/api/contratos/**").uri(uriContratos))
                // 4. Ruteo a Contratos: Cuotas
                .route(p -> p.path("/api/cuotas/**").uri(uriContratos))
                .build();
    }
}