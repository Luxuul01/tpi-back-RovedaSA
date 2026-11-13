package com.rovedasa.catalogo.client.bcra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta de GET /CentralDeDeudores/v1.0/Deudas/ChequesRechazados/{cuit}
 * Si la persona no tiene cheques rechazados el BCRA responde 404.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BcraChequesResponseDTO {
    private Integer status;
    private BcraChequesResultadoDTO results;
}
