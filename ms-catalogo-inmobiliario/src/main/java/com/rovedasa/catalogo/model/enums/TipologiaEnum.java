package com.rovedasa.catalogo.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * Tipología del inmueble. El factor de ajuste multiplica el valor por m² de la zona
 * para obtener el precio sugerido según el tipo de propiedad.
 */
@Getter
@RequiredArgsConstructor
public enum TipologiaEnum {
    DEPARTAMENTO(new BigDecimal("1.00")),
    CASA(new BigDecimal("1.10")),
    PH(new BigDecimal("0.95")),
    LOCAL_COMERCIAL(new BigDecimal("1.30")),
    OFICINA(new BigDecimal("1.15")),
    COCHERA(new BigDecimal("0.40"));

    private final BigDecimal factorAjuste;
}
