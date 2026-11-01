package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.client.bcra.BcraCentralDeudoresClient;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResultadoDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudorDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraEntidadDeudaDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraPeriodoDTO;
import com.rovedasa.catalogo.exception.BcraNoDisponibleException;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * CU-02: califica la solvencia de un garante consultando en tiempo real la
 * Central de Deudores del BCRA por CUIT/CUIL.
 *
 * Devuelve el ValidacionGaranteDTO de commons, que es lo que ms-contratos recibe
 * antes de activar un contrato.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolvenciaGaranteService {

    /** Situación 1 = normal. Es la que corresponde a quien no registra deudas en el sistema financiero. */
    private static final int SITUACION_NORMAL = 1;

    private final BcraCentralDeudoresClient bcraClient;
    private final EvaluadorSolvencia evaluador;

    public ValidacionGaranteDTO evaluar(String cuitCuil, BigDecimal ingresosDeclarados, BigDecimal canonMensual) {
        String cuit = normalizarCuit(cuitCuil);
        log.debug("Evaluando solvencia de garante con CUIT terminado en {}", cuit.substring(7));

        Optional<BcraDeudorDTO> deudas;
        Optional<BcraChequesResultadoDTO> cheques;
        try {
            deudas = bcraClient.obtenerDeudas(cuit);
            cheques = bcraClient.obtenerChequesRechazados(cuit);
        } catch (RestClientException ex) {
            log.warn("Central de Deudores del BCRA no disponible. No se puede evaluar al garante. Causa: {}",
                    ex.getMessage());
            throw new BcraNoDisponibleException(
                    "La Central de Deudores del BCRA no está disponible en este momento. Reintente más tarde.", ex);
        }

        int situacion = deudas.map(this::peorSituacionUltimoPeriodo).orElse(SITUACION_NORMAL);
        int cantidadCheques = cheques.map(this::contarCheques).orElse(0);
        String denominacion = deudas.map(BcraDeudorDTO::getDenominacion)
                .or(() -> cheques.map(BcraChequesResultadoDTO::getDenominacion))
                .orElse(null);

        EvaluadorSolvencia.Resultado resultado =
                evaluador.evaluar(situacion, cantidadCheques, ingresosDeclarados, canonMensual);

        log.info("Garante CUIT ***{} evaluado: solvente={} (situación {}, cheques rechazados {})",
                cuit.substring(7), resultado.esSolvente(), situacion, cantidadCheques);

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

    /** Acepta "20-12345678-9" o "20123456789" y devuelve los 11 dígitos. */
    String normalizarCuit(String cuitCuil) {
        String cuit = cuitCuil == null ? "" : cuitCuil.replace("-", "").replace(" ", "");
        if (!cuit.matches("\\d{11}")) {
            throw new OperacionInvalidaException("El CUIT/CUIL debe tener 11 dígitos");
        }
        return cuit;
    }

    /** Peor situación (la más alta) entre todas las entidades del período más reciente informado. */
    int peorSituacionUltimoPeriodo(BcraDeudorDTO deudor) {
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
        return ultimo.getEntidades().stream()
                .map(BcraEntidadDeudaDTO::getSituacion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(SITUACION_NORMAL);
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
