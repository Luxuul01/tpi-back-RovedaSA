package com.rovedasa.catalogo.model;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransicionesEstadoPropiedadTest {

    @ParameterizedTest(name = "{0} -> {1} permitida")
    @CsvSource({
            "DISPONIBLE, RESERVADA",
            "RESERVADA, ALQUILADA",
            "RESERVADA, DISPONIBLE",
            "ALQUILADA, DISPONIBLE"
    })
    void transicionesValidas(EstadoPropiedadEnum origen, EstadoPropiedadEnum destino) {
        assertTrue(TransicionesEstadoPropiedad.puedeTransicionar(origen, destino));
    }

    @ParameterizedTest(name = "{0} -> {1} rechazada")
    @CsvSource({
            // Saltear la reserva
            "DISPONIBLE, ALQUILADA",
            // Volver atrás desde un alquiler
            "ALQUILADA, RESERVADA",
            // INACTIVA no participa del flujo de contratos
            "DISPONIBLE, INACTIVA",
            "RESERVADA, INACTIVA",
            "ALQUILADA, INACTIVA",
            "INACTIVA, DISPONIBLE",
            "INACTIVA, RESERVADA",
            "INACTIVA, ALQUILADA"
    })
    void transicionesInvalidas(EstadoPropiedadEnum origen, EstadoPropiedadEnum destino) {
        assertFalse(TransicionesEstadoPropiedad.puedeTransicionar(origen, destino));
    }

    @ParameterizedTest(name = "{0} -> {0} rechazada")
    @EnumSource(EstadoPropiedadEnum.class)
    void pasarAlMismoEstadoNoEsUnaTransicion(EstadoPropiedadEnum estado) {
        assertFalse(TransicionesEstadoPropiedad.puedeTransicionar(estado, estado));
    }
}
