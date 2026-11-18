package com.rovedasa.catalogo.model.enums;

/**
 * Roles de NEGOCIO que puede cumplir una persona dentro del catálogo.
 * No confundir con los roles de Keycloak (ADMINISTRADOR, PROPIETARIO, INQUILINO),
 * que gobiernan el acceso a la API.
 */
public enum TipoPersonaEnum {
    PROPIETARIO(true),
    INQUILINO(true),
    // Los garantes no tienen usuario en Keycloak: no consultan la API
    GARANTE(false);

    private final boolean requiereUsuarioKeycloak;

    TipoPersonaEnum(boolean requiereUsuarioKeycloak) {
        this.requiereUsuarioKeycloak = requiereUsuarioKeycloak;
    }

    /**
     * RN-17: PROPIETARIO e INQUILINO ven sus datos filtrados por el sub del token, así que la persona
     * tiene que estar vinculada a su usuario de Keycloak. Sin el vínculo verían listas vacías en silencio.
     */
    public boolean requiereUsuarioKeycloak() {
        return requiereUsuarioKeycloak;
    }
}
