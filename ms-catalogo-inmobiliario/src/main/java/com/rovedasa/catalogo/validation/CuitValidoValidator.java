package com.rovedasa.catalogo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CuitValidoValidator implements ConstraintValidator<CuitValido, String> {

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext contexto) {
        // Null y vacío los rechaza @NotBlank, no este validador
        if (valor == null || valor.isBlank()) {
            return true;
        }
        return ValidadorCuit.esValido(valor);
    }
}
