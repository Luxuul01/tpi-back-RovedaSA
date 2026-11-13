package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Contrato;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fábrica que reconstruye el objeto de comportamiento (EstadoContrato) a partir del
 * EstadoContratoEnum persistido en la base de datos, evitando estructuras condicionales if/else.
 */
@Component
public class EstadoContratoFactory {

    private final Map<EstadoContratoEnum, EstadoContrato> estados = new EnumMap<>(EstadoContratoEnum.class);

    public EstadoContratoFactory() {
        registrar(new EstadoCreado());
        registrar(new EstadoSeniado());
        registrar(new EstadoActivo());
        registrar(new EstadoCancelado());
        registrar(new EstadoRescindido());
        registrar(new EstadoFinalizado());
    }

    @Autowired
    public EstadoContratoFactory(List<EstadoContrato> estadosRegistrados) {
        for (EstadoContrato estado : estadosRegistrados) {
            registrar(estado);
        }
    }

    private void registrar(EstadoContrato estado) {
        estados.put(estado.getTipoEstado(), estado);
    }

    public EstadoContrato obtenerEstado(EstadoContratoEnum tipoEstado) {
        if (tipoEstado == null) {
            throw new OperacionInvalidaException("El estado del contrato no puede ser nulo");
        }
        EstadoContrato estado = estados.get(tipoEstado);
        if (estado == null) {
            throw new OperacionInvalidaException("No existe implementación para el estado: " + tipoEstado);
        }
        return estado;
    }

    public EstadoContrato obtenerEstado(Contrato contrato) {
        if (contrato == null) {
            throw new OperacionInvalidaException("El contrato no puede ser nulo");
        }
        return obtenerEstado(contrato.getEstado());
    }
}
