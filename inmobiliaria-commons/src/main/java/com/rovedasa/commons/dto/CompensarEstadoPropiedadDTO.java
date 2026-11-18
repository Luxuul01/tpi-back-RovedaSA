package com.rovedasa.commons.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body de PATCH /api/propiedades/{id}/estado/compensacion (contrato 2 entre ms-contratos y el catálogo, Saga).
 * Deshace la transición estadoAnterior -> estadoActual, que tiene que ser una transición válida de RN-05.
 * Al volver a ALQUILADA o a RESERVADA hay que reasignar las personas que la transición deshecha desvinculó:
 * inquilinoId es obligatorio para volver a ALQUILADA; para RESERVADA ambos son opcionales, igual que al reservar.
 * Los campos nulos no se envían.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompensarEstadoPropiedadDTO {
    @NotNull(message = "El estado actual es obligatorio")
    private EstadoPropiedadEnum estadoActual;

    @NotNull(message = "El estado anterior es obligatorio")
    private EstadoPropiedadEnum estadoAnterior;

    private Long inquilinoId;
    private Long garanteId;
}
