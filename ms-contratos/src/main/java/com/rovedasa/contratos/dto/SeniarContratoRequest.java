package com.rovedasa.contratos.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO de entrada para seniar un contrato (POST /api/contratos/{id}/seniar).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeniarContratoRequest {

    @NotNull(message = "El monto de la seña es obligatorio")
    @Positive(message = "El monto de la seña debe ser mayor a cero")
    private BigDecimal montoSenia;

    @NotNull(message = "La fecha límite de la seña es obligatoria")
    @Future(message = "La fecha límite de la seña debe ser futura")
    private LocalDate fechaLimiteSenia;
}
