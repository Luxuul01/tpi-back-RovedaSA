package com.rovedasa.contratos.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO de salida para rescindir (POST /api/contratos/{id}/rescindir).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RescisionResponse {

    private Long contratoId;
    private LocalDate fechaRescision;
    private BigDecimal penalizacion;
    private Integer cuotasAnuladas;
}
