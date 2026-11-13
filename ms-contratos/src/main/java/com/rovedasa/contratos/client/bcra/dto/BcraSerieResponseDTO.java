package com.rovedasa.contratos.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Respuesta de GET /estadisticas/v4.0/monetarias/{idVariable}?desde=&hasta=
 *
 * {"status":200,"metadata":{...},"results":[{"idVariable":40,"detalle":[{"fecha":"2026-10-26","valor":37.09}]}]}
 *
 * El BCRA devuelve el detalle ordenado del más reciente al más viejo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraSerieResponseDTO {
    private Integer status;
    private List<BcraSerieResultadoDTO> results;
}
