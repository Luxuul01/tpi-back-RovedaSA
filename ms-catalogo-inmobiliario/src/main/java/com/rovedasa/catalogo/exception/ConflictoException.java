package com.rovedasa.catalogo.exception;

/**
 * Base de los errores de negocio del catálogo que se responden 409 Conflict: la operación es válida pero
 * choca con el estado actual de los datos (un recurso que ya existe o una propiedad que no está en el
 * estado esperado). El mensaje es de negocio y se puede mostrar.
 */
public class ConflictoException extends RuntimeException {

    public ConflictoException(String mensaje) {
        super(mensaje);
    }
}
