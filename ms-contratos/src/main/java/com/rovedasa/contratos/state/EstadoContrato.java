package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Interfaz del Patrón State que modela el comportamiento del ciclo de vida de un Contrato.
 * Por defecto, todas las operaciones están prohibidas y lanzan OperacionInvalidaException (HTTP 400);
 * cada estado concreto sobrescribe únicamente las transiciones legalmente habilitadas.
 */
public interface EstadoContrato {

    EstadoContratoEnum getTipoEstado();

    default void seniar(Contrato contrato, BigDecimal montoSenia, LocalDate fechaLimiteSenia, LocalDate fechaActual) {
        throw OperacionNoPermitida("registrar una seña", contrato);
    }

    default List<Cuota> activar(Contrato contrato, LocalDate fechaActual) {
        throw OperacionNoPermitida("activar el contrato", contrato);
    }

    default void cancelar(Contrato contrato, String motivo, boolean retenerSenia) {
        throw OperacionNoPermitida("cancelar el contrato", contrato);
    }

    default List<Cuota> aplicarAjuste(Contrato contrato, Ajuste nuevoAjuste) {
        throw OperacionNoPermitida("aplicar un ajuste de canon", contrato);
    }

    default BigDecimal rescindir(Contrato contrato, LocalDate fechaRescision) {
        throw OperacionNoPermitida("rescindir el contrato", contrato);
    }

    default void finalizar(Contrato contrato, LocalDate fechaActual) {
        throw OperacionNoPermitida("finalizar el contrato", contrato);
    }

    private OperacionInvalidaException OperacionNoPermitida(String accion, Contrato contrato) {
        String codigo = contrato != null && contrato.getNroContrato() != null
                ? contrato.getNroContrato()
                : "sin-codigo";
        return new OperacionInvalidaException(String.format(
                "No se puede %s sobre el contrato %s porque se encuentra en estado %s",
                accion, codigo, getTipoEstado()));
    }
}
