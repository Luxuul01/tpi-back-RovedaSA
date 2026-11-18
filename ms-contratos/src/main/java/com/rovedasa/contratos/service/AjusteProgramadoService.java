package com.rovedasa.contratos.service;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.client.CatalogoIntegracionException;
import com.rovedasa.contratos.dto.ResumenAjustePeriodicoResponse;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import com.rovedasa.contratos.exception.IndiceNoDisponibleException;
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
 *
 * El motivo de un pendiente es para el cliente: las excepciones de negocio del proyecto conservan su mensaje y
 * cualquier otro error da un motivo genérico (el detalle técnico, como una sentencia SQL, va solo al log). Si dos
 * corridas aplican el mismo ajuste a la vez, la que pierde recibe un conflicto de unicidad o de versión: si el
 * ajuste quedó aplicado por la otra no se informa como pendiente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AjusteProgramadoService {

    static final String USUARIO_SISTEMA = "SISTEMA";
    static final String MOTIVO_ERROR_INTERNO = "Error interno al aplicar el ajuste; buscá el detalle en los logs";
    static final String MOTIVO_YA_APLICADO = "Ya lo aplicó otra ejecución";
    static final String MOTIVO_CONCURRENTE =
            "Otra operación modificó el contrato al mismo tiempo; queda para la próxima corrida";

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
                if (ContratoService.esConflictoDeConcurrencia(e) && quedoAplicado(contratoId, periodo)) {
                    log.warn("Ajuste periódico N° {} ({} a {}) del contrato {}: {} ({})", periodo.numero(),
                            periodo.desde(), periodo.hasta(), contratoId, MOTIVO_YA_APLICADO,
                            e.getClass().getSimpleName());
                    return aplicados;
                }
                registrarPendiente(pendientes, contratoId, String.format("Ajuste N° %d (%s a %s): %s",
                        periodo.numero(), periodo.desde(), periodo.hasta(), motivo(e)), e);
                return aplicados;
            }
        }
    }

    /** Después de un conflicto: si el ajuste del período quedó registrado (lo aplicó la otra ejecución). */
    private boolean quedoAplicado(Long contratoId, PeriodoAjuste periodo) {
        try {
            return contratoService.ajusteYaAplicado(contratoId, periodo.hasta());
        } catch (RuntimeException e) {
            log.warn("No se pudo verificar si el ajuste N° {} del contrato {} quedó aplicado: {}",
                    periodo.numero(), contratoId, e.getMessage());
            return false;
        }
    }

    /** Lo esperable (negocio o concurrencia) va en WARN sin stacktrace; lo inesperado en ERROR con stacktrace. */
    private static void registrarPendiente(List<ResumenAjustePeriodicoResponse.Pendiente> pendientes,
                                           Long contratoId, String motivo, RuntimeException e) {
        if (esErrorDeNegocio(e) || ContratoService.esConflictoDeConcurrencia(e)) {
            log.warn("No se pudo aplicar el ajuste periódico al contrato {}: {} ({}: {})", contratoId, motivo,
                    e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.error("No se pudo aplicar el ajuste periódico al contrato {}: {}", contratoId, motivo, e);
        }
        pendientes.add(ResumenAjustePeriodicoResponse.Pendiente.builder()
                .contratoId(contratoId)
                .motivo(motivo)
                .build());
    }

    /** Motivo que se informa en el resumen: nunca el mensaje de una excepción técnica. */
    private static String motivo(RuntimeException e) {
        if (esErrorDeNegocio(e) && e.getMessage() != null) {
            return e.getMessage();
        }
        if (ContratoService.esConflictoDeConcurrencia(e)) {
            return MOTIVO_CONCURRENTE;
        }
        return MOTIVO_ERROR_INTERNO;
    }

    /** Excepciones propias del proyecto: su mensaje está escrito para el cliente. */
    private static boolean esErrorDeNegocio(RuntimeException e) {
        return e instanceof OperacionInvalidaException
                || e instanceof RecursoNoEncontradoException
                || e instanceof IndiceNoDisponibleException
                || e instanceof CatalogoNoDisponibleException
                || e instanceof CatalogoConflictoException
                || e instanceof CatalogoIntegracionException;
    }
}
