package com.rovedasa.contratos.exception;

/**
 * El índice pedido no se pudo obtener ni del BCRA ni del caché de contingencia.
 * Se responde 503 Service Unavailable: es preferible postergar un ajuste que
 * aplicarlo con un valor equivocado.
 */
public class IndiceNoDisponibleException extends RuntimeException {
    public IndiceNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
