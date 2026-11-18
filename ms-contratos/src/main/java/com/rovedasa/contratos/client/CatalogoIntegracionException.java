package com.rovedasa.contratos.client;

/**
 * El catálogo respondió, pero con un error que no es culpa del cliente de ms-contratos ni una caída:
 * rechazó la credencial del servicio (401/403) u otra respuesta 4xx inesperada. Se responde
 * 502 Bad Gateway (Apunte 18: error en un servicio intermedio), no 503 "no disponible".
 */
public class CatalogoIntegracionException extends RuntimeException {

    public CatalogoIntegracionException(String message) {
        super(message);
    }
}
