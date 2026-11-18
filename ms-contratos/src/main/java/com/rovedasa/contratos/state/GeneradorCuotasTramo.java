package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generador de cuotas por tramo de ajuste.
 * El tramo k (0 al activar, k ≥ 1 para el ajuste k) emite las cuotas i ∈ [k·f, min((k+1)·f, totalMeses)),
 * con el canon vigente. Las cuotas se identifican por período (nroCuota = i + 1), no por cantidad: si una
 * ya existe no se vuelve a emitir. Cada cuota lleva su fecha de emisión (la fecha actual del Clock), desde la
 * que corre la mora si se emite después de su vencimiento.
 */
final class GeneradorCuotasTramo {

    private GeneradorCuotasTramo() {
    }

    static List<Cuota> generarTramo(Contrato contrato, int numeroTramo, Ajuste ajusteOrigen, LocalDate fechaEmision) {
        Integer frecuencia = contrato.getFrecuenciaAjusteMeses();
        if (frecuencia == null || frecuencia <= 0) {
            throw new OperacionInvalidaException("La frecuencia de ajuste en meses debe ser mayor a cero");
        }
        Integer dia = contrato.getDiaVencimientoMes();
        if (dia == null || dia < 1 || dia > 31) {
            throw new OperacionInvalidaException("El día de vencimiento mensual debe estar entre 1 y 31");
        }

        int totalMeses = CalendarioContrato.totalMeses(contrato);
        int desde = numeroTramo * frecuencia;
        int hasta = Math.min(desde + frecuencia, totalMeses);

        Set<Integer> existentes = contrato.getCuotas().stream()
                .map(Cuota::getNroCuota)
                .collect(Collectors.toSet());
        BigDecimal montoCanonTramo = contrato.getMontoActual().setScale(2, RoundingMode.HALF_UP);
        List<Cuota> nuevasCuotas = new ArrayList<>();

        for (int i = desde; i < hasta; i++) {
            int nroCuota = i + 1;
            if (existentes.contains(nroCuota)) {
                continue;
            }
            LocalDate inicioPeriodo = CalendarioContrato.inicioPeriodo(contrato, i);

            Cuota cuota = Cuota.builder()
                    .nroCuota(nroCuota)
                    .periodoMes(inicioPeriodo.getMonthValue())
                    .periodoAnio(inicioPeriodo.getYear())
                    .fechaVencimiento(CalendarioContrato.vencimiento(inicioPeriodo, dia))
                    .fechaEmision(fechaEmision)
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
