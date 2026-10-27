package com.rovedasa.catalogo.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuitValidoValidatorTest {

    private static ValidatorFactory fabrica;
    private static Validator validator;

    private final CuitValidoValidator cuitValidoValidator = new CuitValidoValidator();

    // Record de prueba: confirma que la anotación funciona sobre un componente de record (así serán los DTOs)
    record SolicitudConCuit(@CuitValido String cuitCuil) {
    }

    @BeforeAll
    static void crearValidator() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validator = fabrica.getValidator();
    }

    @AfterAll
    static void cerrarFabrica() {
        fabrica.close();
    }

    @Test
    void debeAceptarCuitValidoConYSinGuiones() {
        assertTrue(cuitValidoValidator.isValid("20123456786", null));
        assertTrue(cuitValidoValidator.isValid("20-12345678-6", null));
    }

    @Test
    void debeRechazarCuitInvalido() {
        assertFalse(cuitValidoValidator.isValid("20123456787", null));
    }

    @Test
    void debeDejarPasarNullYVacio() {
        assertTrue(cuitValidoValidator.isValid(null, null));
        assertTrue(cuitValidoValidator.isValid("", null));
        assertTrue(cuitValidoValidator.isValid("   ", null));
    }

    @Test
    void laAnotacionDebeReportarElMensajePorDefecto() {
        Set<ConstraintViolation<SolicitudConCuit>> violaciones =
                validator.validate(new SolicitudConCuit("20-12345678-7"));

        assertEquals(1, violaciones.size());
        ConstraintViolation<SolicitudConCuit> violacion = violaciones.iterator().next();
        assertEquals("cuitCuil", violacion.getPropertyPath().toString());
        assertEquals("CUIT/CUIL inválido", violacion.getMessage());
    }

    @Test
    void laAnotacionNoDebeReportarNadaConUnCuitValido() {
        assertTrue(validator.validate(new SolicitudConCuit("20-12345678-6")).isEmpty());
    }
}
