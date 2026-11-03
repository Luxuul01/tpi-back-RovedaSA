package com.rovedasa.catalogo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI. El esquema bearer JWT se aplica a todos los endpoints, así Swagger UI
 * muestra el botón "Authorize" para pegar un token de Keycloak.
 */
@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA_JWT = "bearerJwt";

    @Bean
    OpenAPI documentacionCatalogo() {
        return new OpenAPI()
                .info(new Info()
                        .title("ms-catalogo-inmobiliario")
                        .description("Inmuebles y personas del sistema de alquileres: normalización de direcciones "
                                + "con GeoRef AR, tasación por zona y estados de disponibilidad.")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(ESQUEMA_JWT, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_JWT));
    }
}
