package com.rovedasa.catalogo.exception;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;

/**
 * La propiedad no está en el estado que esperaba quien pidió el cambio (estadoEsperado del PATCH de estado
 * o estadoActual de la compensación). Se responde 409: otro proceso la cambió antes y quien llama tiene que
 * volver a leerla. No se toca nada.
 */
public class EstadoPropiedadInesperadoException extends ConflictoException {

    public EstadoPropiedadInesperadoException(EstadoPropiedadEnum actual, EstadoPropiedadEnum esperado) {
        super(String.format("La propiedad está en estado %s y se esperaba %s", actual, esperado));
    }
}
