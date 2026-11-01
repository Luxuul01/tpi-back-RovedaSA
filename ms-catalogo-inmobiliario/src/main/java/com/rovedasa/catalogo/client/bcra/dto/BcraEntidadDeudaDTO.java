package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Deuda con una entidad financiera. situacion: 1 (normal) a 5 (irrecuperable). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraEntidadDeudaDTO {
    private String entidad;
    private Integer situacion;
    private BigDecimal monto;
    private Integer diasAtrasoPago;
}
