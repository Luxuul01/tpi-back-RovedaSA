package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.validation.CuitValido;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

public record CrearPersonaRequest(
        // Acepta el CUIT/CUIL con o sin guiones; se guarda solo con dígitos
        @NotBlank @CuitValido String cuitCuil,
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 100) String apellido,
        @Email @Size(max = 255) String email,
        @Size(max = 30) String telefono,
        // Solo para quien tiene usuario en Keycloak (los garantes no)
        @Size(max = 255) String keycloakUserId,
        @NotEmpty Set<@NotNull TipoPersonaEnum> roles,
        @PositiveOrZero BigDecimal ingresosMensualesDeclarados) {
}
