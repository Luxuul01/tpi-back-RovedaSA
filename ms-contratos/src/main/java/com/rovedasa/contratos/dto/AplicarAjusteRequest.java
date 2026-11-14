package com.rovedasa.contratos.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO de entrada para aplicar un ajuste por ICL (POST /api/contratos/{id}/ajustes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AplicarAjusteRequest {

    @NotNull(message = "La fecha índice desde es obligatoria")
    @PastOrPresent(message = "La fecha índice desde no puede ser futura")
    private LocalDate fechaIndiceDesde;

    @NotNull(message = "La fecha índice hasta es obligatoria")
    @PastOrPresent(message = "La fecha índice hasta no puede ser futura")
    private LocalDate fechaIndiceHasta;
}
