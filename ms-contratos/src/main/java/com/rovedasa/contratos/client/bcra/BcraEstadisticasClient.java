package com.rovedasa.contratos.client.bcra;

import com.rovedasa.contratos.client.bcra.dto.BcraSerieResponseDTO;
import com.rovedasa.contratos.client.bcra.dto.BcraSerieResultadoDTO;
import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Cliente HTTP de la API "Estadísticas Monetarias v4.0" del BCRA.
 *
 * GET /estadisticas/v4.0/monetarias/{idVariable}?desde=yyyy-MM-dd&hasta=yyyy-MM-dd
 *
 * Solo se ocupa de hablar con el BCRA y traducir el JSON a DTOs. Las reglas de
 * negocio (qué valor usar, qué hacer si falla) viven en IndiceService.
 * Ante cualquier problema lanza RestClientException (o una subclase), que es lo
 * que el servicio atrapa para decidir si usa el caché de contingencia.
 *
 * El RestClient se arma acá con el RestClient.Builder de Spring Boot (Apunte 18),
 * así no se publica un bean RestClient suelto que pueda chocar con los de otros
 * clientes del microservicio.
 */
@Slf4j
@Component
public class BcraEstadisticasClient {

    private final RestClient restClient;

    @Autowired
    public BcraEstadisticasClient(RestClient.Builder builder,
                                  @Value("${bcra.api.base-url:https://api.bcra.gob.ar}") String baseUrl,
                                  @Value("${bcra.api.connect-timeout-ms:3000}") int connectTimeoutMs,
                                  @Value("${bcra.api.read-timeout-ms:5000}") int readTimeoutMs) {
        this(builder
                .baseUrl(baseUrl)
                .requestFactory(crearFabricaConTimeouts(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor((request, body, execution) -> {
                    long t0 = System.currentTimeMillis();
                    var response = execution.execute(request, body);
                    log.debug("BCRA -> {} {} | <- {} | {} ms", request.getMethod(), request.getURI(),
                            response.getStatusCode(), System.currentTimeMillis() - t0);
                    return response;
                })
                .build());
    }

    // Recibe el RestClient ya armado: permite enlazar MockRestServiceServer en los tests
    BcraEstadisticasClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Timeouts: si el BCRA tarda más de esto, se corta y el servicio pasa al caché de contingencia. */
    private static SimpleClientHttpRequestFactory crearFabricaConTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(connectTimeoutMs);
        fabrica.setReadTimeout(readTimeoutMs);
        return fabrica;
    }

    public List<BcraValorDTO> obtenerSerie(int idVariable, LocalDate desde, LocalDate hasta) {
        log.debug("Consultando BCRA variable {} entre {} y {}", idVariable, desde, hasta);

        BcraSerieResponseDTO respuesta = restClient.get()
                .uri("/estadisticas/v4.0/monetarias/{idVariable}?desde={desde}&hasta={hasta}",
                        idVariable, desde.toString(), hasta.toString())
                .retrieve()
                .body(BcraSerieResponseDTO.class);

        return extraerValores(respuesta, idVariable);
    }

    /**
     * Último valor publicado en o antes de 'hasta', sin límite hacia atrás.
     * Según la documentación de la API v4.0, 'desde' es opcional (por defecto, el inicio de la serie)
     * y 'limit' indica cuántos registros devolver; la serie viene de la fecha más nueva a la más vieja,
     * así que con limit=1 el BCRA devuelve el último valor hasta esa fecha.
     * Devuelve una lista vacía si la serie no tiene valores hasta esa fecha.
     */
    public List<BcraValorDTO> obtenerUltimoValor(int idVariable, LocalDate hasta) {
        log.debug("Consultando BCRA variable {}: último valor hasta {}", idVariable, hasta);

        BcraSerieResponseDTO respuesta = restClient.get()
                .uri("/estadisticas/v4.0/monetarias/{idVariable}?hasta={hasta}&limit=1",
                        idVariable, hasta.toString())
                .retrieve()
                .body(BcraSerieResponseDTO.class);

        return extraerValores(respuesta, idVariable);
    }

    private List<BcraValorDTO> extraerValores(BcraSerieResponseDTO respuesta, int idVariable) {
        // Respuesta inesperada: 200 pero sin la estructura documentada
        if (respuesta == null || respuesta.getResults() == null) {
            throw new RestClientException(
                    "Respuesta inesperada del BCRA para la variable " + idVariable + ": falta 'results'");
        }

        return respuesta.getResults().stream()
                .filter(r -> r.getIdVariable() != null && r.getIdVariable() == idVariable)
                .map(BcraSerieResultadoDTO::getDetalle)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(v -> v.getFecha() != null && v.getValor() != null)
                .toList();
    }
}
