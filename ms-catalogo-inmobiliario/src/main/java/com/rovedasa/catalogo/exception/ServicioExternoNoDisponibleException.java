package com.rovedasa.catalogo.exception;

/**
 * Una API externa (GeoRef) no respondió, tardó demasiado o devolvió un error.
 */
public class ServicioExternoNoDisponibleException extends RuntimeException {

    public ServicioExternoNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
