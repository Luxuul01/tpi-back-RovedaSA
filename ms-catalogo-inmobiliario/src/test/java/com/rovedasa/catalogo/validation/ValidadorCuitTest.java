package com.rovedasa.catalogo.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pesos: 5,4,3,2,7,6,5,4,3,2 sobre los 10 primeros dígitos; dv = 11 - (suma % 11), 11 -> 0, 10 -> inválido.
 */
class ValidadorCuitTest {

    @Test
    void debeNormalizarDejandoSoloDigitos() {
        assertEquals("20123456786", ValidadorCuit.normalizar("20-12345678-6"));
        assertEquals("20123456786", ValidadorCuit.normalizar(" 20.12345678 6 "));
        assertNull(ValidadorCuit.normalizar(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 2·5 + 0·4 + 1·3 + 2·2 + 3·7 + 4·6 + 5·5 + 6·4 + 7·3 + 8·2 = 148; 148 % 11 = 5; dv = 6
            "20123456786",
            "20-12345678-6",
            // 2·5 + 0·4 + 1·3 + 2·2 + 3·7 + 4·6 + 5·5 + 6·4 + 7·3 + 0·2 = 132; 132 % 11 = 0; dv = 11 -> 0
            "20123456700",
            // Los dígitos 1,2,3,4,5,6,7,8 aportan 3 + 4 + 21 + 24 + 25 + 24 + 21 + 16 = 138
            // 2·5 + 7·4 + 138 = 176; 176 % 11 = 0; dv = 11 -> 0
            "27-12345678-0",
            // 3·5 + 0·4 + 138 = 153; 153 % 11 = 10; dv = 1 (persona jurídica)
            "30123456781"
    })
    void debeAceptarCuitsValidos(String cuit) {
        assertTrue(ValidadorCuit.esValido(cuit), cuit);
    }

    @Test
    void debeRechazarDigitoVerificadorIncorrecto() {
        // El correcto es 6
        assertFalse(ValidadorCuit.esValido("20123456787"));
        assertFalse(ValidadorCuit.esValido("20-12345678-0"));
    }

    @Test
    void debeRechazarPrefijoInvalidoAunqueElDigitoVerificadorCierre() {
        // 2·5 + 1·4 + 138 = 152; 152 % 11 = 9; dv = 2 -> el dígito cierra, pero 21 no es un prefijo válido
        assertFalse(ValidadorCuit.esValido("21123456782"));
    }

    @Test
    void debeRechazarCuandoElDigitoVerificadorCalculadoEs10() {
        // 2,0,1,2,3,4,5,6,7,6: 132 + 6·2 = 144; 144 % 11 = 1; dv = 10 -> inválido para cualquier último dígito
        // (AFIP en ese caso emite el número con prefijo 23)
        for (int ultimo = 0; ultimo <= 9; ultimo++) {
            assertFalse(ValidadorCuit.esValido("2012345676" + ultimo), "último dígito " + ultimo);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2012345678", "201234567860", "20-1234567-6", "2012345678A", ""})
    void debeRechazarLargoIncorrecto(String cuit) {
        assertFalse(ValidadorCuit.esValido(cuit), cuit);
    }

    @Test
    void nullNoEsValido() {
        assertFalse(ValidadorCuit.esValido(null));
    }
}
