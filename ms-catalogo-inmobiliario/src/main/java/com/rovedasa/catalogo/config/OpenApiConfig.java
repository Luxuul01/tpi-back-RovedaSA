package com.rovedasa.catalogo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI. Expone login OAuth2 (Authorization Code + PKCE con Keycloak) y pegado manual de Bearer JWT.
 */
@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA_OAUTH2 = "keycloakOAuth2";
    private static final String ESQUEMA_JWT = "bearerJwt";

    @Bean
    OpenAPI documentacionCatalogo(@Value("${KEYCLOAK_PORT:8083}") String keycloakPort) {
        String oidcBase = "http://localhost:" + keycloakPort + "/realms/rovedasa-realm/protocol/openid-connect";
        return new OpenAPI()
                .info(new Info()
                        .title("ms-catalogo-inmobiliario")
                        .description("Inmuebles y personas del sistema de alquileres: normalización de direcciones "
                                + "con GeoRef AR, tasación por zona y estados de disponibilidad.")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(ESQUEMA_OAUTH2, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(oidcBase + "/auth")
                                        .tokenUrl(oidcBase + "/token")
                                        .scopes(new Scopes()))))
                        .addSecuritySchemes(ESQUEMA_JWT, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_OAUTH2))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_JWT));
    }
}
