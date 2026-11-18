package com.rovedasa.catalogo.exception;

/**
 * Se intentó crear un recurso que ya existe (ej. una persona con el mismo CUIT). Se responde 409.
 */
public class RecursoDuplicadoException extends ConflictoException {

    public RecursoDuplicadoException(String mensaje) {
        super(mensaje);
    }
}
