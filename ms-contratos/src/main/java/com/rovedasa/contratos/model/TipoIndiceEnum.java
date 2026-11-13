package com.rovedasa.contratos.model;

/**
 * Índices de actualización de alquileres que el sistema obtiene del BCRA
 * (API Estadísticas Monetarias v4.0).
 *
 * - ICL: Índice para Contratos de Locación (base 30.6.20 = 1). Serie diaria.
 *        El ajuste se calcula como cociente ICL(hasta) / ICL(desde).
 * - IPC: Inflación mensual en porcentaje (variable 27 del BCRA). Serie mensual.
 *        El ajuste se calcula encadenando las variaciones mensuales:
 *        (1 + v1/100) x (1 + v2/100) x ... x (1 + vN/100).
 */
public enum TipoIndiceEnum {

    ICL(40, "Índice para Contratos de Locación (ICL)"),
    IPC(27, "Inflación mensual (IPC) en porcentaje");

    private final int idVariableBcra;
    private final String descripcion;

    TipoIndiceEnum(int idVariableBcra, String descripcion) {
        this.idVariableBcra = idVariableBcra;
        this.descripcion = descripcion;
    }

    public int getIdVariableBcra() {
        return idVariableBcra;
    }

    public String getDescripcion() {
        return descripcion;
    }
}
