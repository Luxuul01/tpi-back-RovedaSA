package com.rovedasa.catalogo.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlturaValidaValidatorTest {

    private static ValidatorFactory fabrica;
    private static Validator validator;

    private final AlturaValidaValidator alturaValidaValidator = new AlturaValidaValidator();

    // Record de prueba: la anotación sobre un componente de record, como en los DTOs de propiedad
    record SolicitudConAltura(@AlturaValida String numero) {
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

    @ParameterizedTest
    @ValueSource(strings = {"1", "900", "1500", " 900 "})
    void debeAceptarEnterosPositivos(String numero) {
        assertTrue(alturaValidaValidator.isValid(numero, null));
    }

    // RN-03: "0" y "S/N" hacían que GeoRef normalizara la calle sin altura; "-5" ubicaba la altura 5
    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "090", "-5", "+5", "S/N", "s/n", "12B", "9.5", "1 200"})
    void debeRechazarLoQueNoEsUnEnteroPositivo(String numero) {
        assertFalse(alturaValidaValidator.isValid(numero, null));
    }

    @Test
    void debeDejarPasarNullYVacio() {
        assertTrue(alturaValidaValidator.isValid(null, null));
        assertTrue(alturaValidaValidator.isValid("", null));
        assertTrue(alturaValidaValidator.isValid("   ", null));
    }

    @Test
    void laAnotacionDebeReportarElMensajePorDefecto() {
        Set<ConstraintViolation<SolicitudConAltura>> violaciones = validator.validate(new SolicitudConAltura("S/N"));

        assertEquals(1, violaciones.size());
        ConstraintViolation<SolicitudConAltura> violacion = violaciones.iterator().next();
        assertEquals("numero", violacion.getPropertyPath().toString());
        assertEquals("El número de calle tiene que ser un entero positivo, solo dígitos y sin ceros a la izquierda "
                + "(por ejemplo 900)", violacion.getMessage());
    }
}
