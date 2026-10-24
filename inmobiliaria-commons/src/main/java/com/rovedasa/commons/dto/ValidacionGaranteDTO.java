package com.rovedasa.commons.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidacionGaranteDTO {
    private Long personaId;
    private String cuitCuil;
    private String nombreCompleto;
    private Integer situacionCrediticiaBcra;
    private Integer chequesRechazados;
    private BigDecimal ingresosDeclarados;
    private Boolean esSolvente;
    private String motivoEvaluacion;
}
