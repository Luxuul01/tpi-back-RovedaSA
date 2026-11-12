package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
public class EstadoSeniado implements EstadoContrato {

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.SENIADO;
    }

    @Override
    public List<Cuota> activar(Contrato contrato, LocalDate fechaActual) {
        if (fechaActual == null) {
            throw new OperacionInvalidaException("La fecha actual es obligatoria para activar el contrato");
        }
        if (contrato.getFechaLimiteSenia() != null && fechaActual.isAfter(contrato.getFechaLimiteSenia())) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede activar el contrato %s: la seña venció el %s",
                    contrato.getNroContrato(), contrato.getFechaLimiteSenia()));
        }

        if (contrato.getMontoActual() == null) {
            contrato.setMontoActual(contrato.getMontoInicial());
        }

        contrato.setEstado(EstadoContratoEnum.ACTIVO);
        List<Cuota> cuotasIniciales = GeneradorCuotasTramo.generarSiguienteTramo(contrato, null);

        log.info("Contrato {} pasó de SENIADO a ACTIVO. Se generaron {} cuotas iniciales con canon={}",
                contrato.getNroContrato(), cuotasIniciales.size(), contrato.getMontoActual());
        return cuotasIniciales;
    }

    @Override
    public void cancelar(Contrato contrato, String motivo, boolean retenerSenia) {
        if (motivo == null || motivo.isBlank()) {
            throw new OperacionInvalidaException("Debe indicar un motivo para cancelar la seña del contrato");
        }

        contrato.setMotivoCancelacion(motivo.trim());
        contrato.setSeniaRetenida(retenerSenia);
        contrato.setEstado(EstadoContratoEnum.CANCELADO);

        log.info("Contrato {} pasó de SENIADO a CANCELADO (seniaRetenida={}). Motivo: {}",
                contrato.getNroContrato(), retenerSenia, contrato.getMotivoCancelacion());
    }
}
