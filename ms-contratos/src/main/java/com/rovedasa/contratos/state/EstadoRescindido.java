package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import org.springframework.stereotype.Component;

/**
 * Estado terminal post-operativo: vínculo interrumpido anticipadamente con penalización liquidada.
 * No permite ninguna mutación posterior.
 */
@Component
public class EstadoRescindido implements EstadoContrato {

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.RESCINDIDO;
    }
}
