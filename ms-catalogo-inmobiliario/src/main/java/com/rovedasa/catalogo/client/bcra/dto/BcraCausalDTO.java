package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Causal del rechazo (ej. "SIN FONDOS") y entidades donde ocurrió. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraCausalDTO {
    private String causal;
    private List<BcraEntidadChequesDTO> entidades;
}
