package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// keycloakUserId: opcional. Solo se usa si el rol lo exige (PROPIETARIO, INQUILINO) y la persona todavía no está
// vinculada a un usuario de Keycloak (RN-17); el formato UUID y la unicidad los valida PersonaService
public record AgregarRolRequest(
        @NotNull TipoPersonaEnum rol,
        @Size(max = 255) String keycloakUserId) {
}
