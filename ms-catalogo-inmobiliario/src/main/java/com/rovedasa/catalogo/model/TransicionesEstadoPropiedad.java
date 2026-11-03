package com.rovedasa.catalogo.model;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transiciones de estado permitidas vía PATCH /api/propiedades/{id}/estado (las dispara ms-contratos).
 * Vive en el catálogo y no en EstadoPropiedadEnum porque el enum es de inmobiliaria-commons.
 * Pasar al mismo estado no es una transición válida. INACTIVA no participa de este flujo.
 */
public final class TransicionesEstadoPropiedad {

    private static final Map<EstadoPropiedadEnum, Set<EstadoPropiedadEnum>> PERMITIDAS =
            new EnumMap<>(EstadoPropiedadEnum.class);

    static {
        PERMITIDAS.put(EstadoPropiedadEnum.DISPONIBLE, EnumSet.of(EstadoPropiedadEnum.RESERVADA));
        // RESERVADA -> DISPONIBLE: se cae la reserva
        PERMITIDAS.put(EstadoPropiedadEnum.RESERVADA,
                EnumSet.of(EstadoPropiedadEnum.ALQUILADA, EstadoPropiedadEnum.DISPONIBLE));
        // ALQUILADA -> DISPONIBLE: contrato finalizado o rescindido
        PERMITIDAS.put(EstadoPropiedadEnum.ALQUILADA, EnumSet.of(EstadoPropiedadEnum.DISPONIBLE));
    }

    private TransicionesEstadoPropiedad() {
    }

    public static boolean puedeTransicionar(EstadoPropiedadEnum origen, EstadoPropiedadEnum destino) {
        return PERMITIDAS.getOrDefault(origen, Set.of()).contains(destino);
    }
}
