package com.rovedasa.commons.dto;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActualizarEstadoPropiedadDTO {
    @NotNull(message = "El nuevo estado es obligatorio")
    private EstadoPropiedadEnum nuevoEstado;

    private Long inquilinoId;
    private Long garanteId;
}
