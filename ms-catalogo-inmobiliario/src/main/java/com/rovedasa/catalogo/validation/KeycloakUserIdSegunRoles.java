package com.rovedasa.catalogo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida el keycloakUserId de un alta de persona según sus roles (RN-17):
 * obligatorio si los roles incluyen PROPIETARIO o INQUILINO, opcional si es solo GARANTE,
 * y siempre con formato UUID cuando viene. El error se informa sobre el campo keycloakUserId.
 */
@Documented
@Constraint(validatedBy = KeycloakUserIdSegunRolesValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeycloakUserIdSegunRoles {

    String message() default "keycloakUserId inválido para los roles indicados";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
