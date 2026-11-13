package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraChequesResultadoDTO {
    private Long identificacion;
    private String denominacion;
    private List<BcraCausalDTO> causales;
}
