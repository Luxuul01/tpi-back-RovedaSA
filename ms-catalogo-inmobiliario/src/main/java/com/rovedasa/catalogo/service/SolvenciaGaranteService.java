package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.client.bcra.BcraCentralDeudoresClient;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResultadoDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudorDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraEntidadDeudaDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraPeriodoDTO;
import com.rovedasa.catalogo.exception.BcraNoDisponibleException;
import com.rovedasa.catalogo.validation.ValidadorCuit;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CU-02: califica la solvencia de un garante consultando en tiempo real la
 * Central de Deudores del BCRA por CUIT/CUIL.
 *
 * Devuelve el ValidacionGaranteDTO de commons, que es lo que ms-contratos recibe
 * antes de activar un contrato.
 *
 * Una respuesta que no cumple lo documentado (por ejemplo, una situación fuera de 1..6) se trata
 * igual que una caída: BcraNoDisponibleException (503), nunca se califica al garante con datos raros.
 * En los logs el CUIT va siempre enmascarado con ValidadorCuit.enmascarar ("20-****5678-6") y no se loguea el mensaje
 * de la excepción del RestClient, porque trae la URL con el CUIT completo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolvenciaGaranteService {

    /** Situación 1 = normal. Es la que corresponde a quien no registra deudas en el sistema financiero. */
    private static final int SITUACION_NORMAL = 1;
    /** Situación 6 = irrecuperable por disposición técnica: la peor que informa la Central de Deudores. */
    private static final int SITUACION_MAXIMA = 6;
    private static final String MENSAJE_NO_DISPONIBLE =
            "La Central de Deudores del BCRA no está disponible en este momento. Reintentá más tarde.";

    private final BcraCentralDeudoresClient bcraClient;
    private final EvaluadorSolvencia evaluador;

    /**
     * @param ingresosDeclarados solo se informa en la respuesta; RN-07 no lo usa para decidir
     */
    public ValidacionGaranteDTO evaluar(String cuitCuil, BigDecimal ingresosDeclarados) {
        String cuit = normalizarCuit(cuitCuil);
        log.debug("Evaluando solvencia de garante con CUIT {}", ValidadorCuit.enmascarar(cuit));

        Optional<BcraDeudorDTO> deudas;
        Optional<BcraChequesResultadoDTO> cheques;
        try {
            deudas = bcraClient.obtenerDeudas(cuit);
            cheques = bcraClient.obtenerChequesRechazados(cuit);
        } catch (RestClientException ex) {
            // Solo el tipo de error (y el status HTTP si hay): el mensaje trae la URL /Deudas/{cuit} completa
            log.warn("Central de Deudores del BCRA no disponible para el CUIT {}. No se puede evaluar al garante. "
                    + "Causa: {}", ValidadorCuit.enmascarar(cuit), describir(ex));
            throw new BcraNoDisponibleException(MENSAJE_NO_DISPONIBLE, ex);
        }

        int situacion = deudas.map(d -> peorSituacionUltimoPeriodo(d, cuit)).orElse(SITUACION_NORMAL);
        int cantidadCheques = cheques.map(this::contarCheques).orElse(0);
        String denominacion = deudas.map(BcraDeudorDTO::getDenominacion)
                .or(() -> cheques.map(BcraChequesResultadoDTO::getDenominacion))
                .orElse(null);

        EvaluadorSolvencia.Resultado resultado =
                evaluador.evaluar(situacion, cantidadCheques);

        log.info("Garante CUIT {} evaluado: solvente={} (situación {}, cheques rechazados {})",
                ValidadorCuit.enmascarar(cuit), resultado.esSolvente(), situacion, cantidadCheques);

        return ValidacionGaranteDTO.builder()
                .cuitCuil(cuit)
                .nombreCompleto(denominacion)
                .situacionCrediticiaBcra(situacion)
                .chequesRechazados(cantidadCheques)
                .ingresosDeclarados(ingresosDeclarados)
                .esSolvente(resultado.esSolvente())
                .motivoEvaluacion(resultado.motivo())
                .build();
    }

    /** Acepta "20-12345678-9" o "20123456789" y devuelve los 11 dígitos (normaliza con ValidadorCuit). */
    String normalizarCuit(String cuitCuil) {
        String cuit = ValidadorCuit.normalizar(cuitCuil);
        if (cuit == null || cuit.length() != 11) {
            throw new OperacionInvalidaException("El CUIT/CUIL debe tener 11 dígitos");
        }
        return cuit;
    }

    private static String describir(RestClientException ex) {
        return ex instanceof RestClientResponseException respuesta
                ? ex.getClass().getSimpleName() + " (HTTP " + respuesta.getStatusCode().value() + ")"
                : ex.getClass().getSimpleName();
    }

    /**
     * Peor situación (la más alta) entre todas las entidades del período más reciente informado.
     * Una situación fuera de 1..6 es una respuesta inválida del BCRA: BcraNoDisponibleException (503).
     */
    int peorSituacionUltimoPeriodo(BcraDeudorDTO deudor, String cuit) {
        if (deudor.getPeriodos() == null || deudor.getPeriodos().isEmpty()) {
            return SITUACION_NORMAL;
        }
        BcraPeriodoDTO ultimo = deudor.getPeriodos().stream()
                .filter(p -> p.getPeriodo() != null)
                .max(Comparator.comparing(BcraPeriodoDTO::getPeriodo))
                .orElse(deudor.getPeriodos().get(0));

        if (ultimo.getEntidades() == null) {
            return SITUACION_NORMAL;
        }
        List<Integer> situaciones = ultimo.getEntidades().stream()
                .map(BcraEntidadDeudaDTO::getSituacion)
                .filter(Objects::nonNull)
                .toList();
        if (situaciones.stream().anyMatch(s -> s < SITUACION_NORMAL || s > SITUACION_MAXIMA)) {
            log.warn("Respuesta inválida de la Central de Deudores para el CUIT {}: situación fuera de {}..{} {}",
                    ValidadorCuit.enmascarar(cuit), SITUACION_NORMAL, SITUACION_MAXIMA, situaciones);
            throw new BcraNoDisponibleException("La Central de Deudores del BCRA devolvió una respuesta inválida "
                    + "(situación fuera de 1 a 6); no se puede evaluar al garante. Reintentá más tarde.", null);
        }
        return situaciones.stream().max(Integer::compareTo).orElse(SITUACION_NORMAL);
    }

    /** Cantidad total de cheques rechazados informados, sumando todas las causales y entidades. */
    int contarCheques(BcraChequesResultadoDTO resultado) {
        if (resultado.getCausales() == null) {
            return 0;
        }
        return resultado.getCausales().stream()
                .filter(c -> c.getEntidades() != null)
                .flatMap(c -> c.getEntidades().stream())
                .filter(e -> e.getDetalle() != null)
                .mapToInt(e -> e.getDetalle().size())
                .sum();
    }
}
