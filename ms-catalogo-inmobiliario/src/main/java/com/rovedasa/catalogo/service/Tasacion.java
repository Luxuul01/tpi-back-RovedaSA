package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.model.PrecioReferenciaZona;

import java.math.BigDecimal;

/**
 * Resultado de tasar una propiedad. Todo nulo si la ciudad no tiene precios de referencia cargados.
 */
public record Tasacion(PrecioReferenciaZona zona, BigDecimal precioSugeridoM2, BigDecimal canonBaseSugerido) {

    public static Tasacion sinCobertura() {
        return new Tasacion(null, null, null);
    }

    public boolean tieneCobertura() {
        return zona != null;
    }
}
