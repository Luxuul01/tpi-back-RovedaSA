package com.rovedasa.contratos.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.CompensarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.dto.PersonaResumenDTO;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Cliente HTTP hacia ms-catálogo-inmobiliario (puerto 8081, directo, no por gateway).
 * Propaga X-Correlation-ID y la credencial a través del interceptor configurado en RestClientConfig.
 *
 * Traducción de errores del catálogo:
 * 404 → 404, 400/422 → 400, 409 → 409 (CatalogoConflictoException), 401/403 y otros 4xx → 502
 * (CatalogoIntegracionException), 5xx o sin respuesta → 503 (CatalogoNoDisponibleException).
 * Cuando el catálogo manda un ApiError, se reenvía su mensaje.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogoClient {

    private static final ObjectMapper LECTOR_ERRORES = new ObjectMapper();

    private final RestClient catalogoRestClient;

    /**
     * GET /api/propiedades/{id}
     * Deserializa directo a PropiedadDTO (Jackson ignora campos extra).
     */
    public PropiedadDTO obtenerPropiedad(Long propiedadId) {
        return ejecutarLlamada("Propiedad", propiedadId, "Catálogo no disponible",
                () -> catalogoRestClient.get()
                        .uri("/propiedades/{id}", propiedadId)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError,
                                manejadorDeErrores("Propiedad", propiedadId, "Catálogo no disponible"))
                        .body(PropiedadDTO.class));
    }

    /**
     * GET /api/personas/{id}
     * Extrae keycloakUserId y roles para validarlos al crear contratos.
     */
    public PersonaResumenDTO obtenerPersona(Long personaId) {
        return ejecutarLlamada("Persona", personaId, "Catálogo no disponible",
                () -> catalogoRestClient.get()
                        .uri("/personas/{id}", personaId)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError,
                                manejadorDeErrores("Persona", personaId, "Catálogo no disponible"))
                        .body(PersonaResumenDTO.class));
    }

    /**
     * POST /api/personas/{id}/evaluacion-solvencia (sin body)
     * Se invoca en activar(). Si esSolvente es false, el llamador debe rechazar la activación.
     */
    public ValidacionGaranteDTO evaluarGarante(Long garanteId) {
        String indisponible = "BCRA o catálogo no disponible para evaluar garante";
        return ejecutarLlamada("Garante", garanteId, indisponible,
                () -> catalogoRestClient.post()
                        .uri("/personas/{id}/evaluacion-solvencia", garanteId)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, manejadorDeErrores("Garante", garanteId, indisponible))
                        .body(ValidacionGaranteDTO.class));
    }

    /**
     * PATCH /api/propiedades/{id}/estado con estado esperado (contrato 1 entre servicios, body
     * ActualizarEstadoPropiedadDTO de commons; los campos nulos no se envían).
     * Si la propiedad no está en {@code estadoEsperado}, el catálogo responde 409 y no la cambia.
     * Usado para: RESERVADA (seña), ALQUILADA (activar), DISPONIBLE (cancelar/rescindir/finalizar).
     * ms-contratos manda siempre el inquilino y el garante del contrato: si la propiedad está RESERVADA o ALQUILADA
     * por otro inquilino, el catálogo responde 409 y no la cambia.
     */
    public PropiedadDTO actualizarEstadoPropiedad(Long propiedadId, EstadoPropiedadEnum nuevoEstado,
                                                  EstadoPropiedadEnum estadoEsperado,
                                                  Long inquilinoId, Long garanteId) {
        ActualizarEstadoPropiedadDTO body = ActualizarEstadoPropiedadDTO.builder()
                .nuevoEstado(nuevoEstado).estadoEsperado(estadoEsperado)
                .inquilinoId(inquilinoId).garanteId(garanteId)
                .build();
        String indisponible = "Catálogo no disponible al cambiar estado de propiedad";
        return ejecutarLlamada("Propiedad", propiedadId, indisponible,
                () -> catalogoRestClient.patch()
                        .uri("/propiedades/{id}/estado", propiedadId)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, manejadorDeErrores("Propiedad", propiedadId, indisponible))
                        .body(PropiedadDTO.class));
    }

    /**
     * PATCH /api/propiedades/{id}/estado/compensacion (contrato 2 entre servicios, body
     * CompensarEstadoPropiedadDTO de commons): deshace una transición
     * de la Saga, de {@code estadoActual} a {@code estadoAnterior}. 409 si la propiedad ya no está en
     * estadoActual; 400 si el par no es el inverso de una transición válida (RN-05).
     * ms-contratos manda siempre el inquilino y el garante del contrato: para volver a ALQUILADA o a RESERVADA
     * desde DISPONIBLE el catálogo los necesita (al liberarla los borró), y en las demás el catálogo verifica que
     * la propiedad siga tomada por ese inquilino (409 si es de otro contrato). Si van null no se envían.
     */
    public PropiedadDTO compensarEstadoPropiedad(Long propiedadId, EstadoPropiedadEnum estadoActual,
                                                 EstadoPropiedadEnum estadoAnterior,
                                                 Long inquilinoId, Long garanteId) {
        CompensarEstadoPropiedadDTO body = CompensarEstadoPropiedadDTO.builder()
                .estadoActual(estadoActual).estadoAnterior(estadoAnterior)
                .inquilinoId(inquilinoId).garanteId(garanteId)
                .build();
        String indisponible = "Catálogo no disponible al compensar el estado de la propiedad";
        return ejecutarLlamada("Propiedad", propiedadId, indisponible,
                () -> catalogoRestClient.patch()
                        .uri("/propiedades/{id}/estado/compensacion", propiedadId)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, manejadorDeErrores("Propiedad", propiedadId, indisponible))
                        .body(PropiedadDTO.class));
    }

    private <T> T ejecutarLlamada(String recurso, Long id, String mensajeIndisponible, Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientResponseException e) {
            log.error("Error HTTP desde catálogo para {} {}: {} {}", recurso, id, e.getStatusCode(), e.getStatusText());
            throw traducirError(e.getStatusCode(), extraerMensaje(e.getResponseBodyAsString()),
                    recurso, id, mensajeIndisponible);
        } catch (RestClientException e) {
            log.error("Error de comunicación con catálogo para {} {}: {}", recurso, id, e.getMessage());
            throw new CatalogoNoDisponibleException(mensajeIndisponible, e);
        }
    }

    private static RestClient.ResponseSpec.ErrorHandler manejadorDeErrores(String recurso, Long id,
                                                                            String mensajeIndisponible) {
        return (request, response) -> {
            log.warn("El catálogo respondió {} para {} {}", response.getStatusCode(), recurso, id);
            throw traducirError(response.getStatusCode(), leerMensaje(response), recurso, id, mensajeIndisponible);
        };
    }

    /** Traduce el estado HTTP del catálogo a la excepción que da la respuesta correcta en ms-contratos. */
    static RuntimeException traducirError(HttpStatusCode status, String mensajeCatalogo, String recurso, Long id,
                                          String mensajeIndisponible) {
        String detalle = mensajeCatalogo != null ? mensajeCatalogo : status.toString();
        int codigo = status.value();
        if (codigo == 404) {
            return new RecursoNoEncontradoException(recurso + " con ID " + id + " no fue encontrado en el catálogo");
        }
        if (codigo == 400 || codigo == 422) {
            return new OperacionInvalidaException(
                    "El catálogo rechazó la operación sobre " + recurso + " con ID " + id + ": " + detalle);
        }
        if (codigo == 409) {
            return new CatalogoConflictoException(
                    "Conflicto en el catálogo sobre " + recurso + " con ID " + id + ": " + detalle);
        }
        if (codigo == 401 || codigo == 403) {
            return new CatalogoIntegracionException("El catálogo rechazó la credencial del servicio (HTTP " + codigo
                    + ") al operar sobre " + recurso + " con ID " + id);
        }
        if (status.is4xxClientError()) {
            return new CatalogoIntegracionException("Respuesta inesperada del catálogo (HTTP " + codigo
                    + ") al operar sobre " + recurso + " con ID " + id + ": " + detalle);
        }
        return new CatalogoNoDisponibleException(mensajeIndisponible + ": " + detalle);
    }

    private static String leerMensaje(ClientHttpResponse response) {
        try (InputStream cuerpo = response.getBody()) {
            return extraerMensaje(new String(cuerpo.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Campo "message" del ApiError del catálogo, si el cuerpo lo trae. */
    private static String extraerMensaje(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            JsonNode mensaje = LECTOR_ERRORES.readTree(cuerpo).get("message");
            return mensaje != null && mensaje.isTextual() && !mensaje.asText().isBlank() ? mensaje.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
