package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.Contrato;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Calendario de un contrato: meses, períodos de cuota, vencimientos e hitos de ajuste.
 * Implementa las convenciones de negocio comunes a ms-contratos (PLAN de arreglos):
 * <ul>
 *   <li>totalMeses = cantidad de i ≥ 0 tales que fechaInicio + i meses &lt; fechaFin.</li>
 *   <li>La cuota i cubre el mes que empieza en fechaInicio + i meses y vence el primer día con número
 *       diaVencimientoMes (recortado al largo del mes) que no sea anterior a ese inicio.</li>
 *   <li>Hito del ajuste k: H_k = fechaInicio + k · frecuencia meses. El ajuste k corresponde si
 *       hoy ≥ H_k, H_k &lt; fechaFin y ya se aplicaron los ajustes 1..k-1. Ventana: H_{k-1} → H_k.</li>
 * </ul>
 * Las fechas se calculan siempre desde fechaInicio (no acumulando plusMonths), así un inicio el día 31
 * no se corre al 28 para siempre después de febrero.
 */
public final class CalendarioContrato {

    private CalendarioContrato() {
    }

    /** Cantidad de meses (cuotas) del contrato. Ej.: 01/01 → 31/12 = 12; 01/12 → 20/12 = 1. */
    public static int totalMeses(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio == null || fechaFin == null) {
            return 0;
        }
        if (!fechaInicio.isBefore(fechaFin)) {
            return 0;
        }
        // MONTHS.between es una cota inferior cercana (puede quedar 1 abajo por el recorte de fin de mes):
        // se parte de ahí para no recorrer mes a mes un contrato con una fecha de fin absurda
        long meses = Math.max(0, ChronoUnit.MONTHS.between(fechaInicio, fechaFin) - 1);
        while (fechaInicio.plusMonths(meses).isBefore(fechaFin)) {
            meses++;
        }
        return Math.toIntExact(meses);
    }

    public static int totalMeses(Contrato contrato) {
        return totalMeses(contrato.getFechaInicio(), contrato.getFechaFin());
    }

    /** Primer día del período que cubre la cuota i (i desde 0). */
    public static LocalDate inicioPeriodo(Contrato contrato, int indiceCuota) {
        return contrato.getFechaInicio().plusMonths(indiceCuota);
    }

    /**
     * Vencimiento de una cuota: el primer día con número {@code diaVencimientoMes} (recortado al largo del mes)
     * que sea igual o posterior al inicio de su período. Ej.: período desde el 17/11 y día 10 → 10/12.
     */
    public static LocalDate vencimiento(LocalDate inicioPeriodo, int diaVencimientoMes) {
        YearMonth mes = YearMonth.from(inicioPeriodo);
        LocalDate candidata = mes.atDay(Math.min(diaVencimientoMes, mes.lengthOfMonth()));
        if (candidata.isBefore(inicioPeriodo)) {
            YearMonth siguiente = mes.plusMonths(1);
            candidata = siguiente.atDay(Math.min(diaVencimientoMes, siguiente.lengthOfMonth()));
        }
        return candidata;
    }

    /** Hito H_k = fechaInicio + k · frecuencia meses (H_0 = fechaInicio). */
    public static LocalDate hito(Contrato contrato, int numeroAjuste) {
        return contrato.getFechaInicio().plusMonths((long) numeroAjuste * contrato.getFrecuenciaAjusteMeses());
    }

    /** Número del próximo ajuste a aplicar: los ajustes se aplican en orden, uno por período. */
    public static int numeroSiguienteAjuste(Contrato contrato) {
        return contrato.getAjustes().size() + 1;
    }

    /** Hito del próximo ajuste (aunque caiga en o después de la fecha de fin). */
    public static LocalDate proximoHito(Contrato contrato) {
        return hito(contrato, numeroSiguienteAjuste(contrato));
    }

    /** Próximo ajuste del contrato sin mirar la fecha actual; vacío si su hito no es anterior a la fecha de fin. */
    public static Optional<PeriodoAjuste> siguienteAjuste(Contrato contrato) {
        int k = numeroSiguienteAjuste(contrato);
        LocalDate hasta = hito(contrato, k);
        if (!hasta.isBefore(contrato.getFechaFin())) {
            return Optional.empty();
        }
        return Optional.of(new PeriodoAjuste(k, hito(contrato, k - 1), hasta));
    }

    /** Próximo ajuste si ya corresponde aplicarlo en {@code fechaActual} (hoy ≥ H_k y H_k &lt; fechaFin). */
    public static Optional<PeriodoAjuste> ajustePendiente(Contrato contrato, LocalDate fechaActual) {
        return siguienteAjuste(contrato).filter(periodo -> !fechaActual.isBefore(periodo.hasta()));
    }
}
