package com.rovedasa.contratos.client.bcra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Un punto de la serie: { "fecha": "2026-10-26", "valor": 37.09 } */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BcraValorDTO {
    private LocalDate fecha;
    private BigDecimal valor;
}
