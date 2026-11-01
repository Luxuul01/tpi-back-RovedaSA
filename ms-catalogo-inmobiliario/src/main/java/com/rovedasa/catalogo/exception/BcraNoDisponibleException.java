package com.rovedasa.catalogo.exception;

/**
 * La Central de Deudores del BCRA no respondió (caída, timeout, 5xx o respuesta inesperada).
 * Se responde 503: no se puede afirmar que un garante es solvente sin consultar su historial.
 * Quien la reciba puede reintentar más tarde o usar la última evaluación guardada de la persona.
 */
public class BcraNoDisponibleException extends RuntimeException {
    public BcraNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
