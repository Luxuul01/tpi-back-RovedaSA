package com.rovedasa.contratos.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Token de la cuenta de servicio {@code backend-services} de Keycloak, con el que ms-contratos llama al
 * catálogo (Alcance, secciones 6 y 10). Se obtiene con el flujo client credentials (Apunte 20) haciendo un
 * POST con RestClient (Apunte 18) al endpoint de tokens del realm, y se reutiliza hasta unos segundos antes
 * de que venza: no se le pide un token nuevo a Keycloak en cada llamada al catálogo.
 *
 * Si Keycloak no responde, rechaza las credenciales o falta el secreto, se lanza
 * CatalogoNoDisponibleException (503): sin token no se puede llamar al catálogo y no se cambia ningún estado.
 */
@Slf4j
public class ProveedorTokenServicio {

    /** Se renueva el token cuando le quedan menos de estos segundos de vida. */
    static final Duration MARGEN_RENOVACION = Duration.ofSeconds(30);

    private final RestClient keycloak;
    private final String clientId;
    private final String clientSecret;
    private final Clock reloj;

    private String tokenActual;
    private Instant renovarDesde;

    /**
     * @param keycloak     cliente con baseUrl en el endpoint de tokens ({@code .../protocol/openid-connect/token})
     * @param clientId     cliente confidencial de Keycloak (backend-services)
     * @param clientSecret secreto del cliente (variable de entorno KEYCLOAK_CLIENT_SECRET)
     * @param reloj        reloj para calcular el vencimiento (fijo en los tests)
     */
    public ProveedorTokenServicio(RestClient keycloak, String clientId, String clientSecret, Clock reloj) {
        this.keycloak = keycloak;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.reloj = reloj;
    }

    /**
     * Devuelve el token vigente o pide uno nuevo si no hay o está por vencer.
     * Sincronizado: dos llamadas simultáneas al catálogo no piden dos tokens.
     */
    public synchronized String obtenerToken() {
        Instant ahora = reloj.instant();
        if (tokenActual == null || !ahora.isBefore(renovarDesde)) {
            pedirToken(ahora);
        }
        return tokenActual;
    }

    /**
     * Descarta el token guardado (por ejemplo, si el catálogo lo rechazó con 401): la próxima llamada pide otro.
     */
    public synchronized void invalidar() {
        tokenActual = null;
        renovarDesde = null;
    }

    private void pedirToken(Instant ahora) {
        if (clientSecret == null || clientSecret.isBlank()) {
            log.error("No está configurado el secreto de la cuenta de servicio (variable KEYCLOAK_CLIENT_SECRET)");
            throw new CatalogoNoDisponibleException("No se puede llamar al catálogo: falta configurar el secreto de "
                    + "la cuenta de servicio de Keycloak (variable de entorno KEYCLOAK_CLIENT_SECRET)");
        }

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("client_id", clientId);
        formulario.add("client_secret", clientSecret);

        RespuestaToken respuesta;
        try {
            respuesta = keycloak.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulario)
                    .retrieve()
                    .body(RespuestaToken.class);
        } catch (RestClientResponseException e) {
            // El cuerpo de Keycloak (invalid_client, etc.) va al log; al cliente solo el motivo general
            log.error("Keycloak rechazó el pedido de token de servicio: {} {}", e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new CatalogoNoDisponibleException("No se pudo obtener el token de servicio para llamar al catálogo "
                    + "(Keycloak respondió " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.error("Keycloak no respondió al pedir el token de servicio: {}", e.getMessage());
            throw new CatalogoNoDisponibleException(
                    "No se pudo obtener el token de servicio para llamar al catálogo (Keycloak no disponible)", e);
        }

        if (respuesta == null || respuesta.accessToken() == null || respuesta.accessToken().isBlank()) {
            throw new CatalogoNoDisponibleException(
                    "No se pudo obtener el token de servicio para llamar al catálogo (respuesta vacía de Keycloak)");
        }

        long vidaSegundos = Math.max(respuesta.expiresIn(), 0);
        // Con tokens muy cortos (menos que el doble del margen) se renueva a mitad de su vida
        long usableSegundos = Math.max(vidaSegundos - MARGEN_RENOVACION.toSeconds(), vidaSegundos / 2);
        tokenActual = respuesta.accessToken();
        renovarDesde = ahora.plusSeconds(usableSegundos);
        log.debug("Token de servicio obtenido de Keycloak; vence en {} s, se renueva en {} s",
                vidaSegundos, usableSegundos);
    }

    /** Parte de la respuesta del endpoint de tokens que se usa. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaToken(@JsonProperty("access_token") String accessToken,
                          @JsonProperty("expires_in") long expiresIn) {
    }
}
