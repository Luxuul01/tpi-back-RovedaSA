package com.rovedasa.contratos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValorIndiceDTO {
    private TipoIndiceEnum tipoIndice;
    /** Fecha pedida por el cliente. */
    private LocalDate fechaSolicitada;
    /** Fecha real del valor usado (último publicado en o antes de la fecha solicitada). */
    private LocalDate fechaIndice;
    /** ICL: valor del índice. IPC: variación mensual en %. */
    private BigDecimal valor;
    private OrigenDatoEnum origen;
}
