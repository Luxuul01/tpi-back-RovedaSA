package com.rovedasa.contratos.client.bcra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Elemento de "results": { "idVariable": 40, "detalle": [ ... ] } */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BcraSerieResultadoDTO {
    private Integer idVariable;
    private List<BcraValorDTO> detalle;
}
