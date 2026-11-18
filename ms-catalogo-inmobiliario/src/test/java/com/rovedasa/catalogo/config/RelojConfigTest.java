package com.rovedasa.catalogo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelojConfigTest {

    @Test
    @DisplayName("Sin app.reloj.fecha-fija el reloj es el del sistema (comportamiento por defecto)")
    void sinPropiedadUsaElRelojDelSistema() {
        Clock reloj = RelojConfig.crearReloj("", ZoneOffset.UTC);

        assertEquals(Clock.system(ZoneOffset.UTC), reloj);
        assertEquals(Clock.system(ZoneOffset.UTC), RelojConfig.crearReloj(null, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Con app.reloj.fecha-fija la fecha de la aplicación queda fija en ese día")
    void conPropiedadFijaLaFecha() {
        Clock reloj = RelojConfig.crearReloj("2027-03-15", ZoneOffset.UTC);

        assertEquals(LocalDate.of(2027, 3, 15), LocalDate.now(reloj));
    }

    @Test
    @DisplayName("Una fecha mal escrita impide arrancar (no se cae al reloj del sistema sin avisar)")
    void fechaInvalidaFalla() {
        assertThrows(RuntimeException.class, () -> RelojConfig.crearReloj("15/03/2027", ZoneOffset.UTC));
    }
}
