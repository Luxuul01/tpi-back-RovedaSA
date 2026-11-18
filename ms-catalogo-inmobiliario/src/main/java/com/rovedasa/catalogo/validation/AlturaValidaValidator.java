package com.rovedasa.catalogo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class AlturaValidaValidator implements ConstraintValidator<AlturaValida, String> {

    // Entero positivo: el primer dígito no es cero ("900" sí; "0", "090", "-5", "S/N" o "12B" no)
    private static final Pattern ALTURA = Pattern.compile("^[1-9][0-9]*$");

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext contexto) {
        // Null y vacío los rechaza @NotBlank, no este validador
        if (valor == null || valor.isBlank()) {
            return true;
        }
        // El servicio guarda el número recortado, así que los espacios en los extremos no cuentan
        return ALTURA.matcher(valor.trim()).matches();
    }
}
