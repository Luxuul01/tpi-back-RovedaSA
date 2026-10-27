package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

public record PersonaResponse(
        Long id,
        String cuitCuil,
        String nombre,
        String apellido,
        String nombreCompleto,
        String email,
        String telefono,
        String keycloakUserId,
        Set<TipoPersonaEnum> roles,
        BigDecimal ingresosMensualesDeclarados,
        Integer situacionCrediticiaBcra,
        Integer chequesRechazados,
        LocalDateTime fechaConsultaBcra,
        LocalDateTime fechaAlta) {
}
