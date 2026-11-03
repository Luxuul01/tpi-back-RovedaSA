package com.rovedasa.catalogo.model.enums;

/**
 * Roles de NEGOCIO que puede cumplir una persona dentro del catálogo.
 * No confundir con los roles de Keycloak (ADMINISTRADOR, PROPIETARIO, INQUILINO),
 * que gobiernan el acceso a la API.
 */
public enum TipoPersonaEnum {
    PROPIETARIO,
    INQUILINO,
    GARANTE
}
