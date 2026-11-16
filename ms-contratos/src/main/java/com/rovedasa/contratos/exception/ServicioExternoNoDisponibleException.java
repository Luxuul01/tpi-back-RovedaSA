package com.rovedasa.contratos.exception;

/**
 * Excepción lanzada cuando ms-catalogo-inmobiliario o un servicio externo requerido
 * (por ejemplo BCRA al evaluar solvencia del garante) no está disponible.
 * Se mapea a HTTP 503 Service Unavailable.
 */
public class ServicioExternoNoDisponibleException extends RuntimeException {

    public ServicioExternoNoDisponibleException(String message) {
        super(message);
    }

    public ServicioExternoNoDisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
