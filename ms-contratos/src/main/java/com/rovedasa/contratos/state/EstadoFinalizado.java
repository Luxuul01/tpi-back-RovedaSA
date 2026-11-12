package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import org.springframework.stereotype.Component;

/**
 * Estado terminal por conclusión regular del plazo contractual.
 * No permite ninguna mutación posterior.
 */
@Component
public class EstadoFinalizado implements EstadoContrato {

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.FINALIZADO;
    }
}
