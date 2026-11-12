package com.rovedasa.contratos.config;

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

    /**
     * Define la especificación OpenAPI de ms-contratos con autenticación HTTP Bearer JWT.
     */
    @Bean
    OpenAPI documentacionContratos() {
        return new OpenAPI()
                .info(new Info()
                        .title("ms-contratos")
                        .description("Gestion del ciclo de vida de contratos de alquiler (Patron State), "
                                + "cuotas, mora e indexacion ICL.")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(ESQUEMA_JWT, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_JWT));
    }
}

