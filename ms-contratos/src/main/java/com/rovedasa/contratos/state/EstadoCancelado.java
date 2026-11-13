package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import org.springframework.stereotype.Component;

/**
 * Estado terminal pre-operativo: contrato descartado o seña caída.
 * No permite ninguna mutación posterior.
 */
@Component
public class EstadoCancelado implements EstadoContrato {

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.CANCELADO;
    }
}
