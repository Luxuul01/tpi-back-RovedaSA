package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta de GET /CentralDeDeudores/v1.0/Deudas/{cuit}
 * {"status":200,"results":{"identificacion":...,"denominacion":"...","periodos":[...]}}
 * Si la persona no registra deudas el BCRA responde 404.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraDeudasResponseDTO {
    private Integer status;
    private BcraDeudorDTO results;
}
