package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import jakarta.validation.constraints.NotNull;

public record AgregarRolRequest(@NotNull TipoPersonaEnum rol) {
}
