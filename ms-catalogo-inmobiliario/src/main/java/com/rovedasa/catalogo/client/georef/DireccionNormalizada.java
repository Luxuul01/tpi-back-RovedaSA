package com.rovedasa.catalogo.client.georef;

/**
 * Dirección devuelta por GeoRef, ya aplanada para el resto del módulo.
 * La ciudad es la localidad censal, que en GeoRef coincide con el nombre de la ciudad
 * (para Córdoba capital: localidad censal "Córdoba", departamento "Capital").
 */
public record DireccionNormalizada(
        String nomenclatura,
        String provincia,
        String ciudad,
        String departamento,
        Double latitud,
        Double longitud) {
}
