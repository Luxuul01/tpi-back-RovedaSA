package com.rovedasa.contratos.dto;

/** De dónde salió el valor del índice usado en un cálculo (queda registrado para auditoría). */
public enum OrigenDatoEnum {
    /** Obtenido en línea desde la API del BCRA. */
    BCRA,
    /** El BCRA no respondió: se usó el valor guardado en la tabla indices_cache. */
    CACHE_CONTINGENCIA
}
