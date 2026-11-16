package com.rovedasa.contratos.exception;

/**
 * Se lanza cuando ms-catalogo-inmobiliario no responde o devuelve 5xx (por ejemplo,
 * 503 cuando la Central de Deudores del BCRA no está disponible al evaluar al garante).
 * Se traduce en HTTP 503 Service Unavailable.
 */
public class CatalogoNoDisponibleException extends RuntimeException {

    public CatalogoNoDisponibleException(String message) {
        super(message);
    }

    public CatalogoNoDisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
