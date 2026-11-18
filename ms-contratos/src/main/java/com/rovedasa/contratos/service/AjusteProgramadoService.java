package com.rovedasa.contratos.service;

import com.rovedasa.contratos.dto.ResumenAjustePeriodicoResponse;
import com.rovedasa.contratos.state.PeriodoAjuste;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proceso de ajuste periódico (F-13): recorre los contratos ACTIVOS y aplica, en orden, todos los ajustes
 * pendientes de cada uno, así un contrato atrasado se pone al día en una sola corrida.
 *
 * No es transaccional: cada ajuste lo aplica ContratoService.aplicarAjuste en su propia transacción corta
 * (y el índice se consulta fuera de ella), así un error en un contrato no revierte los ajustes de los otros.
 * Si un ajuste falla, ese contrato queda pendiente para la próxima corrida (RN-12) y se sigue con el siguiente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AjusteProgramadoService {

    static final String USUARIO_SISTEMA = "SISTEMA";

    private final ContratoService contratoService;

    @Scheduled(cron = "${contratos.ajuste-periodico.cron:0 0 2 * * *}")
    public void ejecutarProgramado() {
        ResumenAjustePeriodicoResponse resumen = ejecutarAjustePeriodico();
        log.info("Ajuste periódico programado: {} contratos revisados, {} ajustes aplicados, {} pendientes",
                resumen.getContratosRevisados(), resumen.getAjustesAplicados(), resumen.getPendientes().size());
    }

    public ResumenAjustePeriodicoResponse ejecutarAjustePeriodico() {
        log.info("Ejecutando ajuste periódico de contratos activos");
        List<Long> contratosActivos = contratoService.listarIdsContratosActivos();
        List<ResumenAjustePeriodicoResponse.Pendiente> pendientes = new ArrayList<>();
        int ajustesAplicados = 0;

        for (Long contratoId : contratosActivos) {
            ajustesAplicados += ponerAlDia(contratoId, pendientes);
        }

        return ResumenAjustePeriodicoResponse.builder()
                .contratosRevisados(contratosActivos.size())
                .ajustesAplicados(ajustesAplicados)
                .pendientes(pendientes)
                .build();
    }

    /** Aplica los ajustes pendientes del contrato hasta que no quede ninguno o uno falle. */
    private int ponerAlDia(Long contratoId, List<ResumenAjustePeriodicoResponse.Pendiente> pendientes) {
        int aplicados = 0;
        while (true) {
            Optional<PeriodoAjuste> pendiente;
            try {
                pendiente = contratoService.buscarAjustePendiente(contratoId);
            } catch (RuntimeException e) {
                registrarPendiente(pendientes, contratoId, "No se pudo revisar el contrato: " + motivo(e), e);
                return aplicados;
            }
            if (pendiente.isEmpty()) {
                return aplicados;
            }

            PeriodoAjuste periodo = pendiente.get();
            try {
                // Sin fechas en el pedido: el período lo fija el contrato
                contratoService.aplicarAjuste(contratoId, null, USUARIO_SISTEMA);
                aplicados++;
                log.info("Ajuste periódico N° {} ({} a {}) aplicado al contrato {}",
                        periodo.numero(), periodo.desde(), periodo.hasta(), contratoId);
            } catch (RuntimeException e) {
                registrarPendiente(pendientes, contratoId, String.format("Ajuste N° %d (%s a %s): %s",
                        periodo.numero(), periodo.desde(), periodo.hasta(), motivo(e)), e);
                return aplicados;
            }
        }
    }

    private static void registrarPendiente(List<ResumenAjustePeriodicoResponse.Pendiente> pendientes,
                                           Long contratoId, String motivo, RuntimeException e) {
        log.warn("No se pudo aplicar el ajuste periódico al contrato {}: {}", contratoId, motivo, e);
        pendientes.add(ResumenAjustePeriodicoResponse.Pendiente.builder()
                .contratoId(contratoId)
                .motivo(motivo)
                .build());
    }

    private static String motivo(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
