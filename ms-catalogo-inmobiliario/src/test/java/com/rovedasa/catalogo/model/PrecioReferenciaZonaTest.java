package com.rovedasa.catalogo.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecioReferenciaZonaTest {

    private final PrecioReferenciaZona nuevaCordoba = zona("Nueva Córdoba", -31.4245, -64.1870, 1200);
    private final PrecioReferenciaZona porDefecto = zona("Córdoba (default)", null, null, null);

    @Test
    void debeContenerUnPuntoDentroDelRadio() {
        // Plaza España, a unos 430 m del centro de la zona
        assertTrue(nuevaCordoba.contienePunto(-31.4280, -64.1850));
    }

    @Test
    void noDebeContenerUnPuntoFueraDelRadio() {
        // Cerro de las Rosas, a varios kilómetros
        assertFalse(nuevaCordoba.contienePunto(-31.3700, -64.2300));
    }

    @Test
    void zonaPorDefectoNoDebeContenerNingunPunto() {
        assertTrue(porDefecto.esValorPorDefecto());
        assertFalse(porDefecto.contienePunto(-31.4245, -64.1870));
        assertThrows(IllegalStateException.class, () -> porDefecto.distanciaAlCentroMetros(-31.4245, -64.1870));
    }

    @Test
    void debeCalcularLaDistanciaConHaversine() {
        PrecioReferenciaZona ecuador = zona("Ecuador", 0.0, 0.0, 1);

        // Un grado de longitud sobre el ecuador mide ~111,19 km
        assertEquals(111_195, ecuador.distanciaAlCentroMetros(0.0, 1.0), 1.0);
        assertEquals(0.0, nuevaCordoba.distanciaAlCentroMetros(-31.4245, -64.1870), 1e-6);
    }

    private PrecioReferenciaZona zona(String nombre, Double latitud, Double longitud, Integer radio) {
        return PrecioReferenciaZona.builder()
                .nombreZona(nombre)
                .provincia("Córdoba")
                .ciudad("Córdoba")
                .latitudCentro(latitud)
                .longitudCentro(longitud)
                .radioMetros(radio)
                .valorM2(new BigDecimal("10000"))
                .puntajeZona(5)
                .fechaActualizacion(LocalDate.now())
                .build();
    }
}
