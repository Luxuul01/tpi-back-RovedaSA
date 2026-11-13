package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Un cheque rechazado. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraChequeDTO {
    private Long nroCheque;
    private String fechaRechazo;
    private BigDecimal monto;
    private String fechaPago;
}
