package com.rovedasa.catalogo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida un CUIT/CUIL con o sin guiones (prefijo y dígito verificador).
 * Null y vacío se consideran válidos: para exigir el valor, combinar con @NotBlank.
 */
@Documented
@Constraint(validatedBy = CuitValidoValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CuitValido {

    String message() default "CUIT/CUIL inválido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
