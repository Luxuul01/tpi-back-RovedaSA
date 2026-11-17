package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Contrato;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Slf4j
@Component
public class EstadoCreado implements EstadoContrato {

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.CREADO;
    }

    @Override
    public void seniar(Contrato contrato, BigDecimal montoSenia, LocalDate fechaLimiteSenia, LocalDate fechaActual) {
        if (montoSenia == null || montoSenia.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OperacionInvalidaException("El monto de la seña debe ser mayor a cero");
        }
        if (fechaLimiteSenia == null || fechaActual == null) {
            throw new OperacionInvalidaException("La fecha límite de la seña y la fecha actual son obligatorias");
        }
        if (fechaLimiteSenia.isBefore(fechaActual)) {
            throw new OperacionInvalidaException("La fecha límite de la seña no puede ser anterior a la fecha actual");
        }

        contrato.setMontoSenia(montoSenia.setScale(2, RoundingMode.HALF_UP));
        contrato.setFechaLimiteSenia(fechaLimiteSenia);
        contrato.setEstado(EstadoContratoEnum.SENIADO);

        log.info("Contrato {} pasó de CREADO a SENIADO con montoSenia={} y fechaLimiteSenia={}",
                contrato.getNroContrato(), contrato.getMontoSenia(), fechaLimiteSenia);
    }

    @Override
    public void cancelar(Contrato contrato, String motivo, boolean retenerSenia) {
        if (motivo == null || motivo.isBlank()) {
            throw new OperacionInvalidaException("Debe indicar un motivo para cancelar el borrador del contrato");
        }

        contrato.setMotivoCancelacion(motivo.trim());
        contrato.setSeniaRetenida(false);
        contrato.setEstado(EstadoContratoEnum.CANCELADO);

        log.info("Contrato borrador {} pasó de CREADO a CANCELADO. Motivo: {}",
                contrato.getNroContrato(), contrato.getMotivoCancelacion());
    }
}
