package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Período informado, formato AAAAMM (ej. "202608"). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraPeriodoDTO {
    private String periodo;
    private List<BcraEntidadDeudaDTO> entidades;
}
