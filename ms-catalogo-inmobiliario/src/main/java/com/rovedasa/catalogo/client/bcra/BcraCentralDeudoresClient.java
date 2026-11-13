package com.rovedasa.catalogo.client.bcra;

import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResponseDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResultadoDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudasResponseDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudorDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * Cliente HTTP de la API "Central de Deudores v1.0" del BCRA.
 *
 *  GET /CentralDeDeudores/v1.0/Deudas/{cuit}                    -> situación crediticia por entidad
 *  GET /CentralDeDeudores/v1.0/Deudas/ChequesRechazados/{cuit}  -> cheques rechazados
 *
 * El BCRA responde 404 cuando la persona no tiene registros: eso NO es un error,
 * significa "sin deudas" o "sin cheques rechazados", y se devuelve Optional.empty().
 * Cualquier otro problema (caída, timeout, 5xx, respuesta inesperada) se propaga
 * como RestClientException para que el servicio decida qué hacer.
 *
 * El RestClient se arma acá con el RestClient.Builder de Spring Boot (Apunte 18), igual
 * que GeoRefClient, así no se publica un bean RestClient suelto que choque con otros clientes.
 */
@Slf4j
@Component
public class BcraCentralDeudoresClient {

    private final RestClient restClient;

    @Autowired
    public BcraCentralDeudoresClient(RestClient.Builder builder,
                                     @Value("${bcra.api.base-url:https://api.bcra.gob.ar}") String baseUrl,
                                     @Value("${bcra.api.connect-timeout-ms:3000}") int connectTimeoutMs,
                                     @Value("${bcra.api.read-timeout-ms:5000}") int readTimeoutMs) {
        this(builder
                .baseUrl(baseUrl)
                .requestFactory(crearFabricaConTimeouts(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor((request, body, execution) -> {
                    long t0 = System.currentTimeMillis();
                    var response = execution.execute(request, body);
                    // No se loguea el CUIT completo: es un dato personal (Apunte 18: evitar datos sensibles en logs)
                    log.debug("BCRA -> {} {} | <- {} | {} ms", request.getMethod(),
                            request.getURI().getPath().replaceAll("\\d{7}(\\d{4})$", "*******$1"),
                            response.getStatusCode(), System.currentTimeMillis() - t0);
                    return response;
                })
                .build());
    }

    // Recibe el RestClient ya armado: permite enlazar MockRestServiceServer en los tests
    BcraCentralDeudoresClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory crearFabricaConTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(connectTimeoutMs);
        fabrica.setReadTimeout(readTimeoutMs);
        return fabrica;
    }

    public Optional<BcraDeudorDTO> obtenerDeudas(String cuit) {
        try {
            BcraDeudasResponseDTO respuesta = restClient.get()
                    .uri("/CentralDeDeudores/v1.0/Deudas/{cuit}", cuit)
                    .retrieve()
                    .body(BcraDeudasResponseDTO.class);
            if (respuesta == null || respuesta.getResults() == null) {
                throw new RestClientException("Respuesta inesperada del BCRA (Deudas): falta 'results'");
            }
            return Optional.of(respuesta.getResults());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    public Optional<BcraChequesResultadoDTO> obtenerChequesRechazados(String cuit) {
        try {
            BcraChequesResponseDTO respuesta = restClient.get()
                    .uri("/CentralDeDeudores/v1.0/Deudas/ChequesRechazados/{cuit}", cuit)
                    .retrieve()
                    .body(BcraChequesResponseDTO.class);
            if (respuesta == null || respuesta.getResults() == null) {
                throw new RestClientException("Respuesta inesperada del BCRA (ChequesRechazados): falta 'results'");
            }
            return Optional.of(respuesta.getResults());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw ex;
        }
    }
}
