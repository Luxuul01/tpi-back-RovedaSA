package com.rovedasa.contratos.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO de entrada para crear un borrador de contrato (POST /api/contratos).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrearContratoRequest {

    @NotNull(message = "El ID de la propiedad es obligatorio")
    private Long propiedadId;

    @NotNull(message = "El ID del propietario es obligatorio")
    private Long propietarioId;

    @NotNull(message = "El ID del inquilino es obligatorio")
    private Long inquilinoId;

    @NotNull(message = "El ID del garante es obligatorio")
    private Long garanteId;

    @NotNull(message = "La fecha de inicio es obligatoria")
    @FutureOrPresent(message = "La fecha de inicio no puede ser pasada")
    private LocalDate fechaInicio;

    @NotNull(message = "La fecha de fin es obligatoria")
    @Future(message = "La fecha de fin debe ser futura")
    private LocalDate fechaFin;

    @NotNull(message = "La frecuencia de ajuste es obligatoria")
    @Positive(message = "La frecuencia de ajuste debe ser mayor a cero")
    private Integer frecuenciaAjusteMeses;

    @NotNull(message = "El monto inicial es obligatorio")
    @Positive(message = "El monto inicial debe ser mayor a cero")
    private BigDecimal montoInicial;

    @NotNull(message = "El porcentaje de mora diaria es obligatorio")
    @PositiveOrZero(message = "El porcentaje de mora diaria no puede ser negativo")
    private BigDecimal porcentajeMoraDiaria;

    @NotNull(message = "El día de vencimiento mensual es obligatorio")
    @Min(value = 1, message = "El día de vencimiento mensual debe estar entre 1 y 31")
    @Max(value = 31, message = "El día de vencimiento mensual debe estar entre 1 y 31")
    private Integer diaVencimientoMes;
}
