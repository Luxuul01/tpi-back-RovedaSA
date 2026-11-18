package com.rovedasa.contratos.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Configuración del cliente HTTP hacia ms-catalogo-inmobiliario.
 * Se registra calificado como "catalogoRestClient" para evitar colisiones con otros RestClient
 * del microservicio (por ejemplo, BcraEstadisticasClient).
 *
 * Cada llamada lleva:
 * - X-Correlation-ID: el de la petición en curso (MDC), para seguir la operación en los logs de los dos servicios.
 * - Authorization: Bearer con el token de la cuenta de servicio backend-services (client credentials), no el del
 *   usuario. Así las llamadas funcionan igual desde un endpoint, desde el ajuste programado (sin usuario) o en la
 *   compensación de la Saga, y el catálogo las autoriza por el rol ADMINISTRADOR de la cuenta de servicio.
 */
@Slf4j
@Configuration
public class RestClientConfig {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Construye el cliente HTTP para comunicarse con ms-catalogo-inmobiliario con timeouts,
     * propagación del X-Correlation-ID y el token de servicio.
     */
    @Bean("catalogoRestClient")
    RestClient catalogoRestClient(
            RestClient.Builder builder,
            ProveedorTokenServicio proveedorTokenServicio,
            @Value("${catalogo.service.base-url:http://localhost:8081/api}") String baseUrl,
            @Value("${catalogo.service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${catalogo.service.read-timeout-ms:12000}") int readTimeoutMs) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(crearFabricaConTimeouts(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor(crearInterceptorCatalogo())
                // El del token va último: si el catálogo responde 401 reintenta una vez con un token nuevo
                .requestInterceptor(crearInterceptorTokenServicio(proveedorTokenServicio))
                .build();
    }

    /**
     * Proveedor del token de la cuenta de servicio. Usa su propio RestClient apuntando al endpoint de tokens
     * de Keycloak; el token recién se pide en la primera llamada al catálogo (no al arrancar).
     */
    @Bean
    ProveedorTokenServicio proveedorTokenServicio(
            RestClient.Builder builder,
            @Value("${keycloak.cuenta-servicio.token-uri}") String tokenUri,
            @Value("${keycloak.cuenta-servicio.client-id:backend-services}") String clientId,
            @Value("${keycloak.cuenta-servicio.client-secret:}") String clientSecret,
            @Value("${keycloak.cuenta-servicio.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${keycloak.cuenta-servicio.read-timeout-ms:5000}") int readTimeoutMs) {
        RestClient keycloak = builder
                .baseUrl(tokenUri)
                .requestFactory(crearFabricaConTimeouts(connectTimeoutMs, readTimeoutMs))
                .build();
        return new ProveedorTokenServicio(keycloak, clientId, clientSecret, Clock.systemUTC());
    }

    /**
     * Interceptor que propaga X-Correlation-ID hacia ms-catalogo-inmobiliario y registra la latencia en DEBUG.
     */
    ClientHttpRequestInterceptor crearInterceptorCatalogo() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            }

            long inicio = System.currentTimeMillis();
            var response = execution.execute(request, body);
            log.debug("Catalogo -> {} {} | <- {} | {} ms",
                    request.getMethod(), request.getURI(), response.getStatusCode(),
                    System.currentTimeMillis() - inicio);
            return response;
        };
    }

    /**
     * Interceptor que pone el token de la cuenta de servicio en Authorization (si la llamada no trae uno
     * explícito). Si el catálogo responde 401 (por ejemplo, Keycloak se reinició y cambió sus claves), descarta
     * el token y reintenta una sola vez con uno nuevo: un 401 significa que el catálogo no procesó la petición.
     */
    static ClientHttpRequestInterceptor crearInterceptorTokenServicio(ProveedorTokenServicio proveedor) {
        return (request, body, execution) -> {
            if (request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return execution.execute(request, body);
            }
            request.getHeaders().setBearerAuth(proveedor.obtenerToken());
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                return response;
            }
            log.warn("El catálogo rechazó el token de servicio (401); se pide uno nuevo y se reintenta una vez");
            response.close();
            proveedor.invalidar();
            request.getHeaders().setBearerAuth(proveedor.obtenerToken());
            return execution.execute(request, body);
        };
    }

    /**
     * Usa JdkClientHttpRequestFactory (java.net.http.HttpClient) en lugar de SimpleClientHttpRequestFactory
     * porque HttpURLConnection no soporta el método HTTP PATCH hacia ms-catalogo-inmobiliario.
     */
    private static ClientHttpRequestFactory crearFabricaConTimeouts(
            int connectTimeoutMs, int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(httpClient);
        fabrica.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return fabrica;
    }
}
