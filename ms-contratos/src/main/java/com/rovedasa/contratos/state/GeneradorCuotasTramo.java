package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Generador de lotes de cuotas bajo demanda por tramo de ajuste.
 * Al activar un contrato o aplicar un ajuste periódico, emite únicamente las cuotas
 * correspondientes al tramo vigente (frecuenciaAjusteMeses), sin exceder la duración total del contrato.
 */
final class GeneradorCuotasTramo {

    private GeneradorCuotasTramo() {
    }

    static List<Cuota> generarSiguienteTramo(Contrato contrato, Ajuste ajusteOrigen) {
        if (contrato.getFrecuenciaAjusteMeses() == null || contrato.getFrecuenciaAjusteMeses() <= 0) {
            throw new OperacionInvalidaException("La frecuencia de ajuste en meses debe ser mayor a cero");
        }
        if (contrato.getDiaVencimientoMes() == null || contrato.getDiaVencimientoMes() < 1 || contrato.getDiaVencimientoMes() > 31) {
            throw new OperacionInvalidaException("El día de vencimiento mensual debe estar entre 1 y 31");
        }

        YearMonth mesInicio = YearMonth.from(contrato.getFechaInicio());
        YearMonth mesFin = YearMonth.from(contrato.getFechaFin());
        long totalMesesContrato = ChronoUnit.MONTHS.between(mesInicio, mesFin);
        if (totalMesesContrato <= 0) {
            totalMesesContrato = contrato.getFrecuenciaAjusteMeses();
        }

        int cuotasExistentes = contrato.getCuotas().size();
        int cuotasRestantes = (int) Math.max(0, totalMesesContrato - cuotasExistentes);
        int cantidadAGenerar = Math.min(contrato.getFrecuenciaAjusteMeses(), cuotasRestantes);

        BigDecimal montoCanonTramo = contrato.getMontoActual().setScale(2, RoundingMode.HALF_UP);
        List<Cuota> nuevasCuotas = new ArrayList<>(cantidadAGenerar);

        for (int i = 0; i < cantidadAGenerar; i++) {
            int nroCuota = cuotasExistentes + i + 1;
            YearMonth periodo = mesInicio.plusMonths(cuotasExistentes + i);
            int diaValido = Math.min(contrato.getDiaVencimientoMes(), periodo.lengthOfMonth());
            LocalDate fechaVencimiento = periodo.atDay(diaValido);

            Cuota cuota = Cuota.builder()
                    .nroCuota(nroCuota)
                    .periodoMes(periodo.getMonthValue())
                    .periodoAnio(periodo.getYear())
                    .fechaVencimiento(fechaVencimiento)
                    .montoBase(montoCanonTramo)
                    .estado(EstadoCuotaEnum.PENDIENTE)
                    .ajuste(ajusteOrigen)
                    .build();

            contrato.agregarCuota(cuota);
            nuevasCuotas.add(cuota);
        }

        return nuevasCuotas;
    }
}
