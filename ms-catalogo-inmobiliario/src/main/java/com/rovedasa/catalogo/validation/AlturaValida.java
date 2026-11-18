package com.rovedasa.catalogo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida la altura (número de calle) de una dirección: entero positivo, solo dígitos y sin cero inicial.
 * GeoRef necesita la altura para ubicar la propiedad (RN-03): con "0" o "S/N" normaliza la calle entera y con
 * "-5" ubica otra altura. Null y vacío se consideran válidos: para exigir el valor, combinar con @NotBlank.
 */
@Documented
@Constraint(validatedBy = AlturaValidaValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AlturaValida {

    String message() default "El número de calle tiene que ser un entero positivo, solo dígitos y sin ceros "
            + "a la izquierda (por ejemplo 900)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
