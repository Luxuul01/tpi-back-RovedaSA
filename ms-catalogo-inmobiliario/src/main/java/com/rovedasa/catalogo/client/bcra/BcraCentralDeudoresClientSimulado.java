package com.rovedasa.catalogo.client.bcra;

import com.rovedasa.catalogo.client.bcra.dto.BcraCausalDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequeDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResultadoDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudorDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraEntidadChequesDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraEntidadDeudaDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraPeriodoDTO;
import com.rovedasa.catalogo.demo.SimuladorServiciosExternos;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Central de Deudores simulada para el perfil "demo" (Alcance, sección 12): el repo no puede usar
 * CUITs de personas reales. Los CUITs de prueba usan DNIs de un rango no asignado (88.888.88x).
 *
 *   20888888811  situación 3, sin cheques           -> NO solvente (C-04, N-06)
 *   20888888838  situación 1, 2 cheques rechazados  -> NO solvente
 *   20888888846  situación 2, sin cheques           -> solvente (riesgo bajo)
 *   cualquier otro                                  -> sin registros (404 del BCRA) -> solvente
 *
 * Con la caída simulada prendida lanza ResourceAccessException, igual que un timeout real,
 * y el servicio responde 503 sin calificar al garante (RN-07, RN-08).
 */
@Slf4j
@Component
@Primary
@Profile("demo")
public class BcraCentralDeudoresClientSimulado extends BcraCentralDeudoresClient {

    public static final String CUIT_SITUACION_3 = "20888888811";
    public static final String CUIT_CON_CHEQUES = "20888888838";
    public static final String CUIT_SITUACION_2 = "20888888846";

    private final SimuladorServiciosExternos simulador;

    public BcraCentralDeudoresClientSimulado(SimuladorServiciosExternos simulador) {
        super(RestClient.create());
        this.simulador = simulador;
    }

    @PostConstruct
    void avisar() {
        log.warn("PERFIL DEMO: la Central de Deudores del BCRA está SIMULADA (no se consulta la API real)");
    }

    @Override
    public Optional<BcraDeudorDTO> obtenerDeudas(String cuit) {
        verificarCaida();
        return switch (cuit) {
            case CUIT_SITUACION_3 -> Optional.of(deudor(cuit, 3));
            case CUIT_SITUACION_2 -> Optional.of(deudor(cuit, 2));
            case CUIT_CON_CHEQUES -> Optional.of(deudor(cuit, 1));
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<BcraChequesResultadoDTO> obtenerChequesRechazados(String cuit) {
        verificarCaida();
        if (!CUIT_CON_CHEQUES.equals(cuit)) {
            return Optional.empty();
        }
        List<BcraChequeDTO> cheques = List.of(
                new BcraChequeDTO(1001L, "2026-05-10", new BigDecimal("150000"), null),
                new BcraChequeDTO(1002L, "2026-06-15", new BigDecimal("90000"), null));
        BcraCausalDTO causal = new BcraCausalDTO("SIN FONDOS",
                List.of(new BcraEntidadChequesDTO(11, cheques)));
        return Optional.of(new BcraChequesResultadoDTO(Long.valueOf(cuit), "PERSONA DE PRUEBA", List.of(causal)));
    }

    private void verificarCaida() {
        if (simulador.isBcraCaido()) {
            throw new ResourceAccessException("Central de Deudores del BCRA simulada como caída (perfil demo)");
        }
    }

    private static BcraDeudorDTO deudor(String cuit, int situacion) {
        BcraEntidadDeudaDTO entidad = new BcraEntidadDeudaDTO("BANCO DE PRUEBA", situacion, new BigDecimal("250"), 0);
        BcraPeriodoDTO periodo = new BcraPeriodoDTO("202609", List.of(entidad));
        return new BcraDeudorDTO(Long.valueOf(cuit), "PERSONA DE PRUEBA", List.of(periodo));
    }
}
