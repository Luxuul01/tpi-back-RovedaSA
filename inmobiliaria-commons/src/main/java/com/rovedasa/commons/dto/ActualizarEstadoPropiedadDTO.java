package com.rovedasa.commons.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body de PATCH /api/propiedades/{id}/estado (contrato 1 entre ms-contratos y el catálogo).
 * estadoEsperado es opcional: si viene y la propiedad no está en ese estado, el catálogo responde 409
 * sin cambiar nada. inquilinoId y garanteId se asignan al reservar. Los campos nulos no se envían.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActualizarEstadoPropiedadDTO {
    @NotNull(message = "El nuevo estado es obligatorio")
    private EstadoPropiedadEnum nuevoEstado;

    private EstadoPropiedadEnum estadoEsperado;

    private Long inquilinoId;
    private Long garanteId;
}
