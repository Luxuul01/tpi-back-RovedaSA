package com.rovedasa.contratos.client;

import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.dto.PersonaResumenDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente HTTP hacia ms-catálogo-inmobiliario (puerto 8081, directo, no por gateway).
 * Propaga X-Correlation-ID y Authorization Bearer a través del interceptor ya configurado
 * en RestClientConfig (Paso 1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogoClient {

    private final RestClient catalogoRestClient;

    /**
     * GET /api/propiedades/{id}
     * Deserializa directo a PropiedadDTO (Jackson ignora campos extra).
     */
    public PropiedadDTO obtenerPropiedad(Long propiedadId) {
        try {
            return catalogoRestClient.get()
                    .uri("/propiedades/{id}", propiedadId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw traducirError4xx(res.getStatusCode(), "Propiedad", propiedadId);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new RuntimeException("Catálogo no disponible: " + res.getStatusCode());
                    })
                    .body(PropiedadDTO.class);
        } catch (RestClientResponseException e) {
            log.error("Error llamando a GET /propiedades/{} : {} {}", propiedadId, e.getStatusCode(), e.getStatusText());
            if (e.getStatusCode().is4xxClientError()) {
                throw traducirError4xx(e.getStatusCode(), "Propiedad", propiedadId);
            }
            throw new RuntimeException("Catálogo no disponible", e);
        }
    }

    /**
     * GET /api/personas/{id}
     * Extrae keycloakUserId para guardarlo en el contrato.
     */
    public PersonaResumenDTO obtenerPersona(Long personaId) {
        try {
            return catalogoRestClient.get()
                    .uri("/personas/{id}", personaId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw traducirError4xx(res.getStatusCode(), "Persona", personaId);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new RuntimeException("Catálogo no disponible: " + res.getStatusCode());
                    })
                    .body(PersonaResumenDTO.class);
        } catch (RestClientResponseException e) {
            log.error("Error llamando a GET /personas/{} : {} {}", personaId, e.getStatusCode(), e.getStatusText());
            if (e.getStatusCode().is4xxClientError()) {
                throw traducirError4xx(e.getStatusCode(), "Persona", personaId);
            }
            throw new RuntimeException("Catálogo no disponible", e);
        }
    }

    /**
     * POST /api/personas/{id}/evaluacion-solvencia (sin body)
     * Se invoca en activar(). Si esSolvente es false, el llamador debe rechazar la activación.
     */
    public ValidacionGaranteDTO evaluarGarante(Long garanteId) {
        try {
            return catalogoRestClient.post()
                    .uri("/personas/{id}/evaluacion-solvencia", garanteId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw traducirError4xx(res.getStatusCode(), "Garante", garanteId);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new RuntimeException("BCRA no disponible para evaluar garante: " + res.getStatusCode());
                    })
                    .body(ValidacionGaranteDTO.class);
        } catch (RestClientResponseException e) {
            log.error("Error llamando a POST /personas/{}/evaluacion-solvencia : {} {}", garanteId, e.getStatusCode(), e.getStatusText());
            if (e.getStatusCode().is4xxClientError()) {
                throw traducirError4xx(e.getStatusCode(), "Garante", garanteId);
            }
            throw new RuntimeException("BCRA no disponible para evaluar garante", e);
        }
    }

    /**
     * PATCH /api/propiedades/{id}/estado
     * Body: ActualizarEstadoPropiedadDTO (nuevoEstado, inquilinoId, garanteId).
     * Usado para: RESERVADA (seña), ALQUILADA (activar), DISPONIBLE (cancelar/rescindir/finalizar).
     */
    public PropiedadDTO actualizarEstadoPropiedad(Long propiedadId, EstadoPropiedadEnum nuevoEstado,
                                                  Long inquilinoId, Long garanteId) {
        try {
            ActualizarEstadoPropiedadDTO body = ActualizarEstadoPropiedadDTO.builder()
                    .nuevoEstado(nuevoEstado)
                    .inquilinoId(inquilinoId)
                    .garanteId(garanteId)
                    .build();

            return catalogoRestClient.patch()
                    .uri("/propiedades/{id}/estado", propiedadId)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw traducirError4xx(res.getStatusCode(), "Propiedad", propiedadId);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new RuntimeException("Catálogo no disponible al cambiar estado: " + res.getStatusCode());
                    })
                    .body(PropiedadDTO.class);
        } catch (RestClientResponseException e) {
            log.error("Error llamando a PATCH /propiedades/{}/estado : {} {}", propiedadId, e.getStatusCode(), e.getStatusText());
            if (e.getStatusCode().is4xxClientError()) {
                throw traducirError4xx(e.getStatusCode(), "Propiedad", propiedadId);
            }
            throw new RuntimeException("Catálogo no disponible al cambiar estado de propiedad", e);
        }
    }

    /**
     * Traduce un 4xx del catálogo a las excepciones de negocio compartidas:
     * 404 -> RecursoNoEncontradoException (HTTP 404), 400/409/422 -> OperacionInvalidaException (HTTP 400).
     * Los errores de credenciales/permisos o de cuota se dejan como error interno para no confundir al cliente.
     */
    private static RuntimeException traducirError4xx(HttpStatusCode status, String recurso, Long id) {
        if (status.value() == 404) {
            return new RecursoNoEncontradoException(
                    recurso + " con ID " + id + " no fue encontrado en el catálogo");
        }
        if (status.value() == 400 || status.value() == 409 || status.value() == 422) {
            return new OperacionInvalidaException(
                    "El catálogo rechazó la operación sobre " + recurso + " con ID " + id + ": " + status);
        }
        return new RuntimeException(
                "El catálogo rechazó la solicitud sobre " + recurso + " con ID " + id + ": " + status);
    }
}
