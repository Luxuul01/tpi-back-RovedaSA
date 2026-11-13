package com.rovedasa.contratos.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

/**
 * Configuración del cliente HTTP hacia ms-catalogo-inmobiliario.
 * Se registra calificado como "catalogoRestClient" para evitar colisiones con otros RestClient
 * del microservicio (por ejemplo, BcraEstadisticasClient).
 */
@Slf4j
@Configuration
public class RestClientConfig {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Construye el cliente HTTP para comunicarse con ms-catalogo-inmobiliario con timeouts
     * y propagación de contexto de trazabilidad y seguridad.
     */
    @Bean("catalogoRestClient")
    RestClient catalogoRestClient(
            RestClient.Builder builder,
            @Value("${catalogo.service.base-url:http://localhost:8081/api}") String baseUrl,
            @Value("${catalogo.service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${catalogo.service.read-timeout-ms:5000}") int readTimeoutMs) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(crearFabricaConTimeouts(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor(crearInterceptorCatalogo())
                .build();
    }

    /**
     * Crea el interceptor que propaga X-Correlation-ID y el token Bearer JWT en las llamadas
     * salientes hacia ms-catalogo-inmobiliario, registrando su latencia en nivel DEBUG.
     */
    ClientHttpRequestInterceptor crearInterceptorCatalogo() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            }

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuth
                    && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(jwtAuth.getToken().getTokenValue());
            }

            long inicio = System.currentTimeMillis();
            var response = execution.execute(request, body);
            log.debug("Catalogo -> {} {} | <- {} | {} ms",
                    request.getMethod(), request.getURI(), response.getStatusCode(),
                    System.currentTimeMillis() - inicio);
            return response;
        };
    }

    private static SimpleClientHttpRequestFactory crearFabricaConTimeouts(
            int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(connectTimeoutMs);
        fabrica.setReadTimeout(readTimeoutMs);
        return fabrica;
    }
}

