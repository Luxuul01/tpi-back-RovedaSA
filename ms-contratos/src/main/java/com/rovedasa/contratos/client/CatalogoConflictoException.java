package com.rovedasa.contratos.client;

/**
 * El catálogo respondió 409: la propiedad no estaba en el estado esperado o hubo un conflicto de
 * concurrencia. Se responde 409 Conflict (no 400): la operación es válida pero otra la ganó.
 */
public class CatalogoConflictoException extends RuntimeException {

    public CatalogoConflictoException(String message) {
        super(message);
    }
}
