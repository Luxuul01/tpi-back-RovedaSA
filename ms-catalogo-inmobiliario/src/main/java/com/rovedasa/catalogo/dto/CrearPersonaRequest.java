package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.validation.CuitValido;
import com.rovedasa.catalogo.validation.KeycloakUserIdSegunRoles;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

// keycloakUserId: obligatorio (UUID) para PROPIETARIO e INQUILINO, opcional para GARANTE (RN-17)
@KeycloakUserIdSegunRoles
public record CrearPersonaRequest(
        // Acepta el CUIT/CUIL como 11 dígitos o con guiones (XX-XXXXXXXX-X); se guarda solo con dígitos
        @NotBlank @CuitValido String cuitCuil,
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 100) String apellido,
        @Email @Size(max = 255) String email,
        @Size(max = 30) String telefono,
        // UUID del usuario en Keycloak (claim sub). Los garantes no tienen usuario
        @Size(max = 255) String keycloakUserId,
        @NotEmpty Set<@NotNull TipoPersonaEnum> roles,
        // Columna NUMERIC(15,2): sin @Digits un valor más grande llegaba a la base
        @PositiveOrZero @Digits(integer = 13, fraction = 2,
                message = "Los ingresos mensuales declarados admiten como máximo 13 dígitos enteros y 2 decimales")
        BigDecimal ingresosMensualesDeclarados) {
}
