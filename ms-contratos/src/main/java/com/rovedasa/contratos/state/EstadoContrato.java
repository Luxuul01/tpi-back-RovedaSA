package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Interfaz del Patrón State que modela el comportamiento del ciclo de vida de un Contrato.
 * Por defecto, todas las operaciones están prohibidas y lanzan OperacionInvalidaException (HTTP 400);
 * cada estado concreto sobrescribe únicamente las transiciones legalmente habilitadas.
 */
public interface EstadoContrato {

    EstadoContratoEnum getTipoEstado();

    /**
     * Estado en el que este estado del contrato tiene ocupada a la propiedad en el catálogo
     * (SEÑADO → RESERVADA, ACTIVO → ALQUILADA). Null si no la ocupa.
     */
    default EstadoPropiedadEnum estadoPropiedadOcupada() {
        return null;
    }

    default void seniar(Contrato contrato, BigDecimal montoSenia, LocalDate fechaLimiteSenia, LocalDate fechaActual) {
        throw operacionNoPermitida("registrar una seña", contrato);
    }

    default List<Cuota> activar(Contrato contrato, LocalDate fechaActual) {
        throw operacionNoPermitida("activar el contrato", contrato);
    }

    default void cancelar(Contrato contrato, String motivo, boolean retenerSenia) {
        throw operacionNoPermitida("cancelar el contrato", contrato);
    }

    /**
     * Valida que se pueda aplicar el próximo ajuste en {@code fechaActual} y que las fechas pedidas
     * (opcionales) coincidan con su ventana. No modifica el contrato.
     */
    default PeriodoAjuste validarAjuste(Contrato contrato, LocalDate fechaActual,
                                        LocalDate desdePedido, LocalDate hastaPedido) {
        throw operacionNoPermitida("aplicar un ajuste de canon", contrato);
    }

    /** Próximo ajuste que ya corresponde aplicar; vacío si no hay ninguno pendiente. */
    default Optional<PeriodoAjuste> ajustePendiente(Contrato contrato, LocalDate fechaActual) {
        return Optional.empty();
    }

    default List<Cuota> aplicarAjuste(Contrato contrato, Ajuste nuevoAjuste) {
        throw operacionNoPermitida("aplicar un ajuste de canon", contrato);
    }

    default BigDecimal rescindir(Contrato contrato, LocalDate fechaRescision, LocalDate fechaActual) {
        throw operacionNoPermitida("rescindir el contrato", contrato);
    }

    default void finalizar(Contrato contrato, LocalDate fechaActual) {
        throw operacionNoPermitida("finalizar el contrato", contrato);
    }

    private OperacionInvalidaException operacionNoPermitida(String accion, Contrato contrato) {
        String codigo = contrato != null && contrato.getNroContrato() != null
                ? contrato.getNroContrato()
                : "sin-codigo";
        return new OperacionInvalidaException(String.format(
                "No se puede %s sobre el contrato %s porque se encuentra en estado %s",
                accion, codigo, getTipoEstado()));
    }
}
