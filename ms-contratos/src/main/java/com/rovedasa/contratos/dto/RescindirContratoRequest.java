package com.rovedasa.contratos.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO de entrada para rescindir un contrato (POST /api/contratos/{id}/rescindir).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RescindirContratoRequest {

    @NotNull(message = "La fecha de rescisión es obligatoria")
    private LocalDate fechaRescision;
}
