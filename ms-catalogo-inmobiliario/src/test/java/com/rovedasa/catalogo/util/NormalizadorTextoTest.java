package com.rovedasa.catalogo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NormalizadorTextoTest {

    @Test
    void debeQuitarAcentos() {
        assertEquals("cordoba", NormalizadorTexto.normalizar("Córdoba"));
        assertEquals("guemes", NormalizadorTexto.normalizar("Güemes"));
    }

    @Test
    void debePasarAMinuscula() {
        assertEquals("rio cuarto", NormalizadorTexto.normalizar("RÍO CUARTO"));
    }

    @Test
    void debeRecortarEspaciosEnLosExtremos() {
        assertEquals("villa maria", NormalizadorTexto.normalizar("   Villa María  "));
    }

    @Test
    void laEnieQuedaComoN() {
        // NFD descompone la ñ en "n" + tilde combinada, y la tilde se borra como cualquier otro acento.
        // Es aceptable para comparar nombres de lugares: "Añelo" y "Anelo" se tratan como iguales.
        assertEquals("anelo", NormalizadorTexto.normalizar("Añelo"));
        assertEquals("nunoa", NormalizadorTexto.normalizar("ÑUÑOA"));
    }

    @Test
    void nullDevuelveNull() {
        assertNull(NormalizadorTexto.normalizar(null));
    }
}
