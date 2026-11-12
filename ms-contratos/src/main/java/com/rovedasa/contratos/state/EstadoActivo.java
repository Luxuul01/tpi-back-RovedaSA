package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import com.rovedasa.commons.enums.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
public class EstadoActivo implements EstadoContrato {

    private static final int MESES_UMBRAL_MULTA_ALTA = 6;
    private static final BigDecimal FACTOR_MULTA_TEMPRANA = new BigDecimal("1.50");
    private static final BigDecimal FACTOR_MULTA_ESTANDAR = new BigDecimal("1.00");

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.ACTIVO;
    }

    @Override
    public List<Cuota> aplicarAjuste(Contrato contrato, Ajuste nuevoAjuste) {
        if (nuevoAjuste == null || nuevoAjuste.getMontoNuevo() == null
                || nuevoAjuste.getMontoNuevo().compareTo(BigDecimal.ZERO) <= 0) {
            throw new OperacionInvalidaException("El ajuste debe contener un nuevo monto válido mayor a cero");
        }
        if (nuevoAjuste.getFechaIndiceDesde() == null || nuevoAjuste.getFechaIndiceHasta() == null
                || !nuevoAjuste.getFechaIndiceHasta().isAfter(nuevoAjuste.getFechaIndiceDesde())) {
            throw new OperacionInvalidaException("El rango de fechas del índice del ajuste es inválido");
        }

        boolean ajusteDuplicado = contrato.getAjustes().stream()
                .anyMatch(existente -> nuevoAjuste.getFechaIndiceHasta().equals(existente.getFechaIndiceHasta()));
        if (ajusteDuplicado) {
            throw new OperacionInvalidaException(String.format(
                    "Ya existe un ajuste aplicado para la fecha de corte %s en el contrato %s",
                    nuevoAjuste.getFechaIndiceHasta(), contrato.getNroContrato()));
        }

        LocalDate fechaRefAjuste = nuevoAjuste.getFechaAplicacion() != null
                ? nuevoAjuste.getFechaAplicacion()
                : nuevoAjuste.getFechaIndiceHasta();

        LocalDate fechaUltimoHito = contrato.getAjustes().stream()
                .map(existente -> existente.getFechaAplicacion() != null
                        ? existente.getFechaAplicacion()
                        : existente.getFechaIndiceHasta())
                .max(LocalDate::compareTo)
                .orElse(contrato.getFechaInicio());

        long mesesDesdeUltimoHito = ChronoUnit.MONTHS.between(fechaUltimoHito, fechaRefAjuste);
        if (mesesDesdeUltimoHito < contrato.getFrecuenciaAjusteMeses()) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede aplicar el ajuste al contrato %s: aún no se cumplió el período mínimo de %d meses",
                    contrato.getNroContrato(), contrato.getFrecuenciaAjusteMeses()));
        }

        BigDecimal canonActualizado = nuevoAjuste.getMontoNuevo().setScale(2, RoundingMode.HALF_UP);
        nuevoAjuste.setMontoNuevo(canonActualizado);
        contrato.agregarAjuste(nuevoAjuste);
        contrato.setMontoActual(canonActualizado);

        List<Cuota> nuevasCuotas = GeneradorCuotasTramo.generarSiguienteTramo(contrato, nuevoAjuste);

        log.info("Ajuste aplicado al contrato {}: nuevo canon={} ({}%). Se emitieron {} cuotas",
                contrato.getNroContrato(), canonActualizado, nuevoAjuste.getPorcentajeVariacion(), nuevasCuotas.size());
        return nuevasCuotas;
    }

    @Override
    public BigDecimal rescindir(Contrato contrato, LocalDate fechaRescision) {
        if (fechaRescision == null) {
            throw new OperacionInvalidaException("La fecha de rescisión es obligatoria");
        }
        if (fechaRescision.isBefore(contrato.getFechaInicio())) {
            throw new OperacionInvalidaException("La fecha de rescisión no puede ser anterior al inicio del contrato");
        }

        long mesesTranscurridos = ChronoUnit.MONTHS.between(contrato.getFechaInicio(), fechaRescision);
        BigDecimal factorMulta = mesesTranscurridos < MESES_UMBRAL_MULTA_ALTA
                ? FACTOR_MULTA_TEMPRANA
                : FACTOR_MULTA_ESTANDAR;

        BigDecimal penalizacion = contrato.getMontoActual()
                .multiply(factorMulta)
                .setScale(2, RoundingMode.HALF_UP);

        int cuotasAnuladas = 0;
        for (Cuota cuota : contrato.getCuotas()) {
            if (cuota.getEstado() == EstadoCuotaEnum.PENDIENTE
                    && cuota.getFechaPago() == null
                    && cuota.getFechaVencimiento().isAfter(fechaRescision)) {
                cuota.anular();
                cuotasAnuladas++;
            }
        }

        contrato.setFechaRescision(fechaRescision);
        contrato.setPenalizacionRescision(penalizacion);
        contrato.setEstado(EstadoContratoEnum.RESCINDIDO);

        log.info("Contrato {} pasó de ACTIVO a RESCINDIDO (antigüedad={} meses, penalización={}, cuotasAnuladas={})",
                contrato.getNroContrato(), mesesTranscurridos, penalizacion, cuotasAnuladas);
        return penalizacion;
    }

    @Override
    public void finalizar(Contrato contrato, LocalDate fechaActual) {
        if (fechaActual == null) {
            throw new OperacionInvalidaException("La fecha actual es obligatoria para finalizar el contrato");
        }
        if (fechaActual.isBefore(contrato.getFechaFin())) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede finalizar el contrato %s antes de su fecha de vencimiento (%s)",
                    contrato.getNroContrato(), contrato.getFechaFin()));
        }

        contrato.setEstado(EstadoContratoEnum.FINALIZADO);

        log.info("Contrato {} pasó de ACTIVO a FINALIZADO por cumplimiento de plazo en fecha {}",
                contrato.getNroContrato(), fechaActual);
    }
}
