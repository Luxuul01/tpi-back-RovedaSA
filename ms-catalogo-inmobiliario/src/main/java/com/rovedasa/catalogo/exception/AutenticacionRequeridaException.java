package com.rovedasa.catalogo.exception;

/**
 * El endpoint necesita la identidad del usuario (claim sub del JWT) y la petición no la trae. Se responde 401.
 */
public class AutenticacionRequeridaException extends RuntimeException {

    public AutenticacionRequeridaException(String mensaje) {
        super(mensaje);
    }
}
