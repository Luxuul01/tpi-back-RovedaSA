package com.rovedasa.catalogo.client.georef;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la API GeoRef AR (prefijo georef.api en application.yml).
 */
@ConfigurationProperties(prefix = "georef.api")
public record GeoRefProperties(
        String baseUrl,
        @DefaultValue("3000") int connectTimeoutMs,
        @DefaultValue("5000") int readTimeoutMs) {
}
